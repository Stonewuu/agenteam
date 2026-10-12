package com.stonewu.agenteam.service.modelprofile.protocol;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.models.responses.ResponseCreateParams;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.anthropic.core.ClientOptions.builder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实官方开发库访问隔离服务，验证认证、路径和流式解码没有被传输适配破坏。
 */
class NativeModelHttpClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger dispatches = new AtomicInteger();
    private HttpServer server;
    private ExecutorService workers;
    private String baseUrl;
    private volatile String response;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Captured(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                    json.readTree(exchange.getRequestBody())));
                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void openaiResponsesUsesOfficialStreamingDecoder() throws Exception {
        response = """
            event: response.completed
            data: {"type":"response.completed","sequence_number":0,"response":{"id":"resp-unit","object":"response","status":"completed","model":"unit","output":[],"usage":{"input_tokens":2,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens":1,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":3}}}

            """;
        try (var session = session()) {
            var client = new OpenAIClientImpl(ClientOptions.builder().httpClient(new OpenAISdkHttpClient(session))
                .apiKey("test-key").baseUrl(baseUrl + "/v1").maxRetries(0).build());
            try (AutoCloseable closeClient = client::close;
                var stream = client.responses().createStreaming(ResponseCreateParams.builder()
                    .model("unit").input("测试请求").store(false).build())) {
                var event = stream.stream().findFirst().orElseThrow();
                assertEquals(3, event.completed().orElseThrow().response().usage().orElseThrow().totalTokens());
            }
        }
        var request = captured();
        assertEquals("/v1/responses", request.path());
        assertEquals("Bearer test-key", request.authorization());
        assertFalse(request.body().path("store").asBoolean(true));
        assertEquals(1, dispatches.get());
    }

    @Test
    void anthropicUsesItsNativeAuthenticationAndEventTypes() throws Exception {
        response = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg-unit","type":"message","role":"assistant","model":"unit","content":[],"usage":{"input_tokens":2,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
        try (var session = session()) {
            var backend = AnthropicBackend.builder().apiKey("test-key").baseUrl(baseUrl).build();
            var client = new AnthropicClientImpl(builder().httpClient(new AnthropicSdkHttpClient(session, backend))
                .baseUrl(baseUrl).maxRetries(0).build());
            try (AutoCloseable closeClient = client::close;
                var stream = client.messages().createStreaming(MessageCreateParams.builder()
                    .model("unit").maxTokens(16).addUserMessage("测试请求").build())) {
                var event = stream.stream().findFirst().orElseThrow();
                assertEquals(2, event.messageStart().orElseThrow().message().usage().inputTokens());
            }
        }
        var request = captured();
        assertEquals("/v1/messages", request.path());
        assertEquals("test-key", request.anthropicKey());
        assertEquals(1, dispatches.get());
    }

    @Test
    void geminiKeepsOfficialRequestConversionAndResponseParsing() throws Exception {
        response = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"完成"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1,"thoughtsTokenCount":0,"cachedContentTokenCount":0,"totalTokenCount":3},"modelVersion":"unit"}

            """;
        try (var session = session(); var client = new GeminiSdkApiClient("test-key", baseUrl, session);
            var stream = new Models(client).generateContentStream("unit",
                List.of(Content.builder().role("user").parts(Part.fromText("测试请求")).build()),
                GenerateContentConfig.builder().maxOutputTokens(16).build())) {
            var first = stream.iterator().next();
            assertEquals(3, first.usageMetadata().orElseThrow().totalTokenCount().orElseThrow());
            assertEquals("完成", first.text());
        }
        var request = captured();
        assertEquals("/v1beta/models/unit:streamGenerateContent", request.path());
        assertEquals("test-key", request.geminiKey());
        assertEquals(1, dispatches.get());
    }

    @Test
    void cancellationDuringRegistrationPreventsTheLaterNetworkRequest() {
        var holder = new AtomicReference<ModelHttpSession>();
        try (var session = new ModelHttpSession(baseUrl, new OutboundAddressPolicy(""),
            () -> holder.get().cancel())) {
            holder.set(session);
            assertThrows(IOException.class, () -> session.execute(new Request.Builder().url(baseUrl).build()));
            assertTrue(requests.isEmpty());
        }
    }

    private ModelHttpSession session() {
        return new ModelHttpSession(baseUrl, new OutboundAddressPolicy(""), dispatches::incrementAndGet);
    }

    private Captured captured() throws InterruptedException {
        var result = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("POST", result.method());
        return result;
    }

    private record Captured(String path, String method, String authorization, String anthropicKey,
                             String geminiKey, JsonNode body) {
    }
}
