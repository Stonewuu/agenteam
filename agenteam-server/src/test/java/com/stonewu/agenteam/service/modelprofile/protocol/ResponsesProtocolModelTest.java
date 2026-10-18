package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.modelprofile.protocol.openai.ResponsesModelSession;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import okhttp3.Request;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行官方 Responses 模型和实际智能体循环，不用替代模型模拟协议行为。
 */
class ResponsesProtocolModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final List<ModelUsageAccumulator> usages = new CopyOnWriteArrayList<>();
    private final List<ModelHttpSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger dispatches = new AtomicInteger();
    private HttpServer server;
    private ExecutorService workers;
    private String baseUrl;
    private volatile int httpStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("/v1/responses", exchange.getRequestURI().getPath());
                assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                requests.add(json.readTree(exchange.getRequestBody()));
                String reply = replies.poll();
                byte[] bytes = (reply == null ? "" : reply).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", httpStatus == 200 && reply != null && reply.startsWith("event:")
                    ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(httpStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
        {"input_tokens":120,"output_tokens":5,"total_tokens":125}|partial|not_reported|null|null
        {"input_tokens":120,"output_tokens":5,"total_tokens":125,"input_tokens_details":{"cached_tokens":0}}|complete|reported|0|120
        """)
    void optionalFieldsAreDistinctFromMissingCacheRead(String rawUsage, String status, String cacheState,
                                                       Long cacheRead, Long uncachedInput) throws Exception {
        replies.add(textReply("完成", rawUsage));
        var chunks = model().stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        assertTrue(chunks.stream().flatMap(chunk -> chunk.getContent().stream())
            .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
            .anyMatch(block -> block.getText().equals("完成")));
        var usage = usages.getFirst().snapshot();
        assertEquals(status, usage.status());
        assertEquals(120L, usage.usage().inputTokens());
        assertEquals(cacheRead, usage.usage().cacheReadTokens());
        assertEquals(uncachedInput, usage.usage().uncachedInputTokens());
        assertEquals(cacheState, usage.usage().fieldStates().get("cache_read"));
        assertNull(usage.usage().cacheWriteTokens());
        assertEquals("not_applicable", usage.usage().fieldStates().get("cache_write"));
        assertEquals("resp-test", usage.metadata().responseId());
        assertFalse(captured().path("store").asBoolean(true));
        assertEquals(1, dispatches.get());
    }

    @Test
    void largeNativeCountsRemainLongAndCannotOverflowFrameworkCounters() throws Exception {
        replies.add(textReply("完成", """
            {"input_tokens":3000000000,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},
             "output_tokens":5,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":3000000005}
            """));
        var chunks = model().stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        assertEquals(3000000000L, usages.getFirst().snapshot().usage().inputTokens());
        assertEquals(3000000005L, usages.getFirst().snapshot().usage().totalTokens());
        assertEquals(Integer.MAX_VALUE, chunks.getLast().getUsage().getInputTokens());
        assertEquals("complete", usages.getFirst().snapshot().status());
    }

    @Test
    void realAgentExecutesToolAndReplaysEncryptedReasoningAfterJsonRoundTrip() throws Exception {
        replies.add(toolReply());
        replies.add(textReply("工具已完成", completeUsage()));
        var toolkit = new Toolkit();
        var echo = new EchoTool();
        toolkit.registerTool(echo);
        var agent = ReActAgent.builder().name("测试员工").sysPrompt("调用工具并报告结果")
            .model(model()).toolkit(toolkit).maxIters(3).build();
        var result = agent.call(List.of(user()), RuntimeContext.builder().userId("user")
            .sessionId("session").build()).block(Duration.ofSeconds(10));
        assertNotNull(result);
        assertEquals("工具已完成", result.getTextContent());
        assertEquals("hello", echo.value);
        assertEquals(2, dispatches.get());
        captured();
        var second = captured();
        var input = second.path("input");
        var items = new ArrayList<JsonNode>();
        input.forEach(items::add);
        assertTrue(items.stream().anyMatch(item -> item.path("type").asText().equals("function_call_output")
            && item.path("call_id").asText().equals("call-one")
            && item.path("output").asText().contains("hello")));
        assertTrue(items.stream().anyMatch(item -> item.path("type").asText().equals("reasoning")
            && item.path("encrypted_content").asText().equals("opaque-private-state")
            && item.path("summary").path(0).path("text").asText().equals("先检查输入")));
        assertEquals(2, usages.size());
        assertTrue(usages.stream().allMatch(value -> value.snapshot().status().equals("complete")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "summary", "raw-summary", "summary-raw"})
    void thinkingTextIsVisibleOnceAndPrivateStateIsPreserved(String order) throws Exception {
        String raw = reasoningDelta("reasoning_text", "content_index", "先检查输入");
        String summary = reasoningDelta("reasoning_summary_text", "summary_index", "检查摘要");
        String events = switch (order) {
            case "raw" -> raw;
            case "summary" -> summary;
            case "raw-summary" -> raw + summary;
            default -> summary + raw;
        };
        var terminal = response(completeUsage());
        terminal.withArray("output").add(reasoningItem());
        replies.add(events + event("response.output_text.delta", "\"item_id\":\"msg-one\",\"output_index\":1,"
            + "\"content_index\":0,\"delta\":\"完成\"")
            + event("response.completed", "\"response\":" + terminal));
        var chunks = model().stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        var thinking = chunks.stream().flatMap(chunk -> chunk.getContent().stream())
            .filter(ThinkingBlock.class::isInstance).map(ThinkingBlock.class::cast).toList();
        assertEquals(order.startsWith("raw") ? "先检查输入" : "检查摘要", thinking.stream()
            .map(ThinkingBlock::getThinking).filter(value -> value != null).reduce("", String::concat));
        assertEquals("opaque-private-state", thinking.getLast().getMetadata().get("openai.reasoning.encrypted_content"));
        if (order.contains("raw")) {
            assertEquals("先检查输入", thinking.getLast().getMetadata().get("openai.reasoning.text"));
        }
        assertEquals(1, dispatches.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonStreamingReasoningUsesTextWhenNoSummaryIsPresent(boolean hasSummary) throws Exception {
        var terminal = response(completeUsage());
        var reasoning = reasoningItem();
        if (hasSummary) {
            reasoning.withArray("summary").addObject().put("type", "summary_text").put("text", "检查摘要");
        }
        terminal.withArray("output").add(reasoning);
        replies.add(terminal.toString());
        var chunks = model().stream(List.of(user()), List.of(), GenerateOptions.builder().stream(false).build())
            .collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        var thinking = (ThinkingBlock) chunks.getFirst().getContent().getFirst();
        assertEquals(hasSummary ? "检查摘要" : "先检查输入", thinking.getThinking());
        assertEquals("opaque-private-state", thinking.getMetadata().get("openai.reasoning.encrypted_content"));
        assertEquals("先检查输入", thinking.getMetadata().get("openai.reasoning.text"));
        assertEquals(1, dispatches.get());
    }

    @Test
    void rateLimitIsNotRetriedInsideTheSameRequest() {
        httpStatus = 429;
        replies.add("{\"error\":{\"message\":\"请求过多\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit\"}}");
        assertThrows(RuntimeException.class, () -> model().stream(List.of(user()), List.of(), null)
            .collectList().block(Duration.ofSeconds(8)));
        assertEquals(1, dispatches.get());
        assertEquals("not_reported", usages.getFirst().snapshot().status());
    }

    @Test
    void failedTerminalEventStillRetainsReportedUsage() throws Exception {
        var response = response(completeUsage());
        response.put("status", "failed");
        response.set("error", json.readTree("{\"code\":\"server_error\",\"message\":\"测试故障\"}"));
        replies.add(event("response.failed", "\"response\":" + response));
        assertThrows(RuntimeException.class, () -> model().stream(List.of(user()), List.of(), null)
            .collectList().block(Duration.ofSeconds(8)));
        assertEquals(125L, usages.getFirst().snapshot().usage().totalTokens());
        assertTrue(usages.getFirst().snapshot().terminal());
        assertEquals("failed", usages.getFirst().snapshot().metadata().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"output_text", "reasoning_text", "reasoning_summary_text"})
    void cancellationClosesAnOpenResponseWithoutWaitingForAnotherChunk(String type) throws Exception {
        var release = new CountDownLatch(1);
        var received = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        String chunk = reasoningDelta(type,
            type.equals("reasoning_summary_text") ? "summary_index" : "content_index", "开始");
        server.removeContext("/v1/responses");
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(chunk.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var subscription = model().stream(List.of(user()), List.of(), null)
            .subscribe(value -> {
                var block = value.getContent().getFirst();
                if (type.equals("output_text") ? block instanceof TextBlock
                    : block instanceof ThinkingBlock thinking && "开始".equals(thinking.getThinking())) {
                    received.countDown();
                }
            }, error::set);
        try {
            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), subscription::dispose);
            assertThrows(CancellationException.class, () -> sessions.getFirst()
                .execute(new Request.Builder().url(baseUrl + "/responses").build()));
            assertEquals(1, dispatches.get());
            assertNull(error.get());
            assertFalse(usages.getFirst().snapshot().terminal());
        } finally {
            subscription.dispose();
            release.countDown();
        }
    }

    private Model model() {
        return new Model() {
            @Override
            public String getModelName() {
                return "unit";
            }

            @Override
            public int getContextWindowSize() {
                return 32000;
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                var restored = messages.stream().map(value -> JsonUtils.getJsonCodec()
                    .fromJson(JsonUtils.getJsonCodec().toJson(value), Msg.class)).toList();
                return Flux.using(() -> {
                    var usage = new ModelUsageAccumulator("openai_responses", "standard", Set.of());
                    usages.add(usage);
                    var transport = new ModelHttpSession(baseUrl, new OutboundAddressPolicy(""), dispatches::incrementAndGet);
                    sessions.add(transport);
                    var configured = GenerateOptions.builder().modelName("unit").apiKey("test-key").baseUrl(baseUrl)
                        .stream(true).build();
                    return new ResponsesModelSession(transport, usage, configured, 32000);
                }, session -> session.model().stream(restored, tools, options).doOnCancel(session::cancel),
                    ResponsesModelSession::close);
            }
        };
    }

    private String textReply(String text, String usage) throws Exception {
        String delta = event("response.output_text.delta", "\"item_id\":\"msg-one\",\"output_index\":0,"
            + "\"content_index\":0,\"delta\":" + json.writeValueAsString(text));
        return delta + event("response.completed", "\"response\":" + response(usage));
    }

    private String toolReply() throws Exception {
        var call = json.readTree("""
            {"type":"function_call","id":"item-one","call_id":"call-one","name":"echo",
             "arguments":"","status":"in_progress"}
            """);
        var response = response(completeUsage());
        response.withArray("output").add(reasoningItem());
        return reasoningDelta("reasoning_text", "content_index", "先检查输入")
            + event("response.output_item.added", "\"output_index\":0,\"item\":" + call)
            + event("response.function_call_arguments.delta", "\"item_id\":\"item-one\",\"output_index\":0,"
                + "\"delta\":\"{\\\"value\\\":\\\"hello\\\"}\"")
            + event("response.completed", "\"response\":" + response);
    }

    private ObjectNode reasoningItem() throws Exception {
        return (ObjectNode) json.readTree("""
            {"type":"reasoning","id":"reason-one","summary":[],"encrypted_content":"opaque-private-state",
             "content":[{"type":"reasoning_text","text":"先检查输入"}]}
            """);
    }

    private String reasoningDelta(String type, String index, String text) throws Exception {
        return event("response." + type + ".delta", "\"item_id\":\"reason-one\",\"output_index\":0,\""
            + index + "\":0,\"delta\":" + json.writeValueAsString(text));
    }

    private ObjectNode response(String usage) throws Exception {
        var response = json.createObjectNode();
        response.put("id", "resp-test").put("object", "response").put("created_at", 1)
            .put("status", "completed").put("model", "unit");
        response.putArray("output");
        response.set("usage", json.readTree(usage));
        return response;
    }

    private String event(String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\",\"sequence_number\":0," + fields + "}\n\n";
    }

    private String completeUsage() {
        return """
            {"input_tokens":120,"input_tokens_details":{"cached_tokens":10,"cache_write_tokens":0},
             "output_tokens":5,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":125}
            """;
    }

    private Msg user() {
        return Msg.builder().role(MsgRole.USER).textContent("测试请求").build();
    }

    private JsonNode captured() throws InterruptedException {
        JsonNode value = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull(value);
        return value;
    }

    public static final class EchoTool {
        private String value;

        @Tool(name = "echo", description = "返回输入内容")
        public String echo(@ToolParam(name = "value", description = "内容") String input) {
            value = input;
            return input;
        }
    }
}
