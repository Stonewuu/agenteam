package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.modelprofile.protocol.gemini.GeminiModelSession;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GeminiProtocolModelTest {
    @TempDir
    Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final List<ModelUsageAccumulator> usages = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService workers;
    private String baseUrl;
    private PayloadEncryption encryption;

    @BeforeEach
    void start() throws IOException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        encryption = new PayloadEncryption(new ApplicationSecretKeys(key, "{\"1\":\"" + key + "\"}", "1",
            directory.resolve("keys.properties").toString()), json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/v1beta/models/unit:streamGenerateContent", exchange -> {
            try (exchange) {
                requests.add(json.readTree(exchange.getRequestBody()));
                String reply = replies.poll();
                byte[] data = (reply == null ? "" : reply).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void streamingSignaturesAndTwoUnnamedCallsSurviveEncryptedRestoration() throws Exception {
        replies.add(thinkingAndTools());
        replies.add(event(List.of(text("完成两个工具")), true, usage()));
        var toolkit = new Toolkit();
        var echo = new EchoTool();
        toolkit.registerTool(echo);
        var agent = ReActAgent.builder().name("测试员工").sysPrompt("执行工具后报告结果")
            .model(model()).toolkit(toolkit).maxIters(3).build();
        var result = agent.call(List.of(new UserMessage("测试请求")), RuntimeContext.builder()
            .userId("user").sessionId("session").build()).block(Duration.ofSeconds(10));
        assertNotNull(result);
        assertEquals("完成两个工具", result.getTextContent());
        assertEquals(Set.of("first", "second"), Set.copyOf(echo.values));
        var first = captured();
        assertTrue(first.path("systemInstruction").toString().contains("执行工具后报告结果"));
        assertEquals(2048, first.path("generationConfig").path("maxOutputTokens").asInt());
        var second = captured();
        var assistant = new ArrayList<JsonNode>();
        second.path("contents").forEach(content -> {
            if (content.path("role").asText().equals("model")) {
                content.path("parts").forEach(assistant::add);
            }
        });
        assertEquals(4, assistant.size());
        assertEquals("思考", assistant.get(0).path("text").asText());
        assertTrue(assistant.get(0).path("thought").asBoolean());
        assertEquals(signature("private-thinking"), assistant.get(0).path("thoughtSignature").asText());
        assertEquals("准备", assistant.get(1).path("text").asText());
        assertEquals(signature("private-text"), assistant.get(1).path("thoughtSignature").asText());
        assertEquals(signature("private-tool"), assistant.get(2).path("thoughtSignature").asText());
        assertFalse(assistant.get(2).path("functionCall").hasNonNull("id"));
        assertFalse(assistant.get(3).path("functionCall").hasNonNull("id"));
        var contents = second.path("contents");
        var results = contents.get(contents.size() - 1).path("parts");
        assertEquals(2, results.size());
        assertTrue(results.toString().contains("first"));
        assertTrue(results.toString().contains("second"));
        for (var part : results) {
            assertFalse(part.path("functionResponse").hasNonNull("id"));
        }
        var actual = usages.getFirst().snapshot();
        assertEquals("complete", actual.status());
        assertEquals(12300L, actual.usage().inputTokens());
        assertEquals(4300L, actual.usage().uncachedInputTokens());
        assertEquals(1500L, actual.usage().outputTokens());
        assertEquals(300L, actual.usage().toolUsePromptTokens());
        assertEquals(13800L, actual.usage().totalTokens());
        assertFalse(json.writeValueAsString(actual).contains("private-"));
        try (var files = Files.walk(directory.resolve("state"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains(signature("private-tool")));
            }
        }
    }

    @Test
    void duplicateProviderToolIdsAreRejectedBeforeToolsCanBeMerged() {
        var first = tool("first");
        ((ObjectNode) first.path("functionCall")).put("id", "duplicate");
        var second = tool("second");
        ((ObjectNode) second.path("functionCall")).put("id", "duplicate");
        replies.add(event(List.of(first, second), true, usage()));
        assertThrows(IllegalStateException.class, () -> model().stream(List.of(new UserMessage("测试请求")), List.of(), null)
            .collectList().block(Duration.ofSeconds(8)));
        assertEquals(13800L, usages.getFirst().snapshot().usage().totalTokens());
    }

    @Test
    void streamWithoutFinalReasonRetainsPartialUsage() {
        replies.add(event(List.of(text("部分回复")), false, usage()));
        var chunks = model().stream(List.of(new UserMessage("测试请求")), List.of(), null)
            .collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        assertEquals("partial", usages.getFirst().snapshot().status());
        assertFalse(usages.getFirst().snapshot().terminal());
    }

    @Test
    void originalCountsLargerThanIntegerSurviveTheOfficialSdkConversion() {
        var original = usage().put("promptTokenCount", 3000000000L).put("totalTokenCount", 3000001800L);
        replies.add(event(List.of(text("计量大数")), true, original));
        var chunks = model().stream(List.of(new UserMessage("测试请求")), List.of(), null)
            .collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        assertTrue(chunks.stream().flatMap(chunk -> chunk.getContent().stream())
            .anyMatch(block -> block instanceof TextBlock text && text.getText().contains("计量大数")));
        var snapshot = usages.getFirst().snapshot();
        assertEquals(3000000300L, snapshot.usage().inputTokens());
        assertEquals(3000001800L, snapshot.usage().totalTokens());
        assertEquals("complete", snapshot.status());
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
                stateStore().save("user", "session", "agent_state", AgentState.builder().userId("user")
                    .sessionId("session").context(messages).build());
                var restored = stateStore().get("user", "session", "agent_state", AgentState.class).orElseThrow().getContext();
                return Flux.using(() -> {
                    var accumulator = new ModelUsageAccumulator("gemini", "standard", Set.of());
                    usages.add(accumulator);
                    var http = new ModelHttpSession(baseUrl, new OutboundAddressPolicy(""), () -> {
                    });
                    var configured = GenerateOptions.builder().modelName("unit").baseUrl(baseUrl).apiKey("test-key")
                        .maxCompletionTokens(2048).stream(true).build();
                    return new GeminiModelSession("provider", configured, 32000, http, accumulator, json);
                }, session -> session.stream(restored, tools, options), GeminiModelSession::close);
            }
        };
    }

    private EncryptedAgentStateStore stateStore() {
        return new EncryptedAgentStateStore(directory.resolve("state"), "enterprise:user:run", encryption, json);
    }

    private String thinkingAndTools() {
        var stream = new StringBuilder();
        stream.append(event(List.of(text("思").put("thought", true)), false, null));
        stream.append(event(List.of(text("考").put("thought", true)), false, null));
        stream.append(event(List.of(json.createObjectNode().put("thoughtSignature", signature("private-thinking"))), false, null));
        stream.append(event(List.of(text("准备")), false, null));
        stream.append(event(List.of(text("").put("thoughtSignature", signature("private-text"))), false, null));
        stream.append(event(List.of(tool("first").put("thoughtSignature", signature("private-tool")), tool("second")), true, usage()));
        return stream.toString();
    }

    private ObjectNode text(String value) {
        return json.createObjectNode().put("text", value);
    }

    private ObjectNode tool(String value) {
        var part = json.createObjectNode();
        part.putObject("functionCall").put("name", "echo").putObject("args").put("value", value);
        return part;
    }

    private ObjectNode usage() {
        return json.createObjectNode().put("promptTokenCount", 12000).put("cachedContentTokenCount", 8000)
            .put("toolUsePromptTokenCount", 300).put("candidatesTokenCount", 900).put("thoughtsTokenCount", 600)
            .put("totalTokenCount", 13800);
    }

    private String event(List<ObjectNode> parts, boolean complete, JsonNode usage) {
        var response = json.createObjectNode().put("responseId", "response-test").put("modelVersion", "unit");
        var candidate = response.putArray("candidates").addObject().put("index", 0);
        candidate.putObject("content").put("role", "model").set("parts", json.valueToTree(parts));
        if (complete) {
            candidate.put("finishReason", "STOP");
        }
        if (usage != null) {
            response.set("usageMetadata", usage);
        }
        return "data: " + response + "\n\n";
    }

    private String signature(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode captured() throws InterruptedException {
        var request = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    public static final class EchoTool {
        private final List<String> values = new CopyOnWriteArrayList<>();

        @Tool(name = "echo", description = "返回输入")
        public String echo(@ToolParam(name = "value", description = "内容") String value) {
            values.add(value);
            return value;
        }
    }
}
