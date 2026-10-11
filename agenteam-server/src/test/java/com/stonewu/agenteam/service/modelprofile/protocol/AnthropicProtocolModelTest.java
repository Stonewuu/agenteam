package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.modelprofile.protocol.anthropic.AnthropicModelSession;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProtocolModelTest {
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
        server.createContext("/v1/messages", exchange -> {
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
    void signedThinkingAndParallelToolsSurviveEncryptedStateReopening() throws Exception {
        replies.add(thinkingAndTools());
        replies.add(textReply("完成两个工具", true));
        var toolkit = new Toolkit();
        var echo = new EchoTool();
        toolkit.registerTool(echo);
        var agent = ReActAgent.builder().name("测试员工").sysPrompt("执行工具后报告结果")
            .model(model()).toolkit(toolkit).maxIters(3).build();
        var result = agent.call(List.of(new UserMessage("测试请求")), RuntimeContext.builder().userId("user")
            .sessionId("session").build()).block(Duration.ofSeconds(10));
        assertNotNull(result);
        assertEquals("完成两个工具", result.getTextContent());
        assertEquals(Set.of("first", "second"), Set.copyOf(echo.values));
        captured();
        var second = captured();
        var messages = second.path("messages");
        var assistant = new ArrayList<JsonNode>();
        messages.forEach(message -> {
            if (message.path("role").asText().equals("assistant")) {
                message.path("content").forEach(assistant::add);
            }
        });
        assertEquals(List.of("thinking", "redacted_thinking", "text", "tool_use", "tool_use"),
            assistant.stream().map(block -> block.path("type").asText()).toList());
        assertEquals("计划", assistant.get(0).path("thinking").asText());
        assertEquals("private-signature-a-b", assistant.get(0).path("signature").asText());
        assertEquals("private-redacted-state", assistant.get(1).path("data").asText());
        assertEquals("准备", assistant.get(2).path("text").asText());
        var finalUser = messages.get(messages.size() - 1);
        assertEquals("user", finalUser.path("role").asText());
        assertEquals(2, finalUser.path("content").size());
        assertTrue(finalUser.path("content").toString().contains("tool-one"));
        assertTrue(finalUser.path("content").toString().contains("tool-two"));
        var usage = usages.getFirst().snapshot();
        assertEquals("complete", usage.status());
        assertEquals(9500L, usage.usage().inputTokens());
        assertEquals(500L, usage.usage().outputTokens());
        assertEquals(100L, usage.usage().reasoningTokens());
        assertEquals(Map.of(300, 2000L, 3600, 500L), usage.usage().cacheWriteDetails());
        assertFalse(json.writeValueAsString(usage).contains("private-"));
        try (var files = Files.walk(directory.resolve("state"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains("private-"));
                assertFalse(Files.readString(file).contains("准备"));
            }
        }
    }

    @Test
    void missingOptionalCacheFieldsDoNotBecomeCompleteBilling() throws Exception {
        replies.add(textReply("仍可回答", false));
        var chunks = model().stream(List.of(new UserMessage("测试请求")), List.of(), null)
            .collectList().block(Duration.ofSeconds(8));
        assertNotNull(chunks);
        var usage = usages.getFirst().snapshot();
        assertEquals("partial", usage.status());
        assertEquals(42L, usage.usage().uncachedInputTokens());
        assertNull(usage.usage().cacheReadTokens());
        assertNull(usage.usage().cacheWriteTokens());
        assertEquals(6L, usage.usage().outputTokens());
        assertTrue(usage.terminal());
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
                var store = stateStore();
                store.save("user", "session", "agent_state", AgentState.builder().userId("user").sessionId("session")
                    .context(messages).build());
                var restored = stateStore().get("user", "session", "agent_state", AgentState.class)
                    .orElseThrow().getContext();
                return Flux.using(() -> {
                    var usage = new ModelUsageAccumulator("anthropic", "standard", Set.of());
                    usages.add(usage);
                    var http = new ModelHttpSession(baseUrl, new OutboundAddressPolicy(""), () -> {
                    });
                    var configured = GenerateOptions.builder().modelName("unit").apiKey("test-key")
                        .baseUrl(baseUrl).stream(true).build();
                    return new AnthropicModelSession("provider", configured, 32000, "5m", http, usage);
                }, session -> session.stream(restored, tools, options), AnthropicModelSession::close);
            }
        };
    }

    private EncryptedAgentStateStore stateStore() {
        return new EncryptedAgentStateStore(directory.resolve("state"), "enterprise:user:run", encryption, json);
    }

    private String thinkingAndTools() throws Exception {
        String usage = """
            {"input_tokens":1000,"output_tokens":0,"cache_read_input_tokens":6000,"cache_creation_input_tokens":2500,
             "cache_creation":{"ephemeral_5m_input_tokens":2000,"ephemeral_1h_input_tokens":500}}
            """;
        var stream = new StringBuilder(startMessage(usage));
        stream.append(startBlock(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}"));
        stream.append(delta(0, "thinking_delta", "thinking", "计划"));
        stream.append(delta(0, "signature_delta", "signature", "private-signature-a"));
        stream.append(delta(0, "signature_delta", "signature", "-b"));
        stream.append(stopBlock(0));
        stream.append(startBlock(1, "{\"type\":\"redacted_thinking\",\"data\":\"private-redacted-state\"}"));
        stream.append(stopBlock(1));
        stream.append(startBlock(2, "{\"type\":\"text\",\"text\":\"\"}"));
        stream.append(delta(2, "text_delta", "text", "准备"));
        stream.append(stopBlock(2));
        for (int index = 3; index < 5; index++) {
            String id = index == 3 ? "tool-one" : "tool-two";
            String value = index == 3 ? "first" : "second";
            stream.append(startBlock(index, "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"echo\",\"input\":{}}"));
            stream.append(delta(index, "input_json_delta", "partial_json", "{\"value\":\"" + value + "\"}"));
            stream.append(stopBlock(index));
        }
        stream.append(event("message_delta", "\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
            + "\"usage\":{\"output_tokens\":500,\"output_tokens_details\":{\"thinking_tokens\":100}}"));
        stream.append(event("message_stop", ""));
        return stream.toString();
    }

    private String textReply(String text, boolean caches) throws Exception {
        return startMessage("{\"input_tokens\":42,\"output_tokens\":0"
            + (caches ? ",\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0" : "") + "}")
            + startBlock(0, "{\"type\":\"text\",\"text\":\"\"}")
            + delta(0, "text_delta", "text", text) + stopBlock(0)
            + event("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":6}")
            + event("message_stop", "");
    }

    private String startMessage(String usage) {
        return event("message_start", "\"message\":{\"id\":\"msg-test\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"unit\",\"content\":[],\"usage\":" + usage + "}");
    }

    private String startBlock(int index, String block) {
        return event("content_block_start", "\"index\":" + index + ",\"content_block\":" + block);
    }

    private String delta(int index, String type, String field, String text) throws Exception {
        return event("content_block_delta", "\"index\":" + index + ",\"delta\":{\"type\":\"" + type + "\",\""
            + field + "\":" + json.writeValueAsString(text) + "}");
    }

    private String stopBlock(int index) {
        return event("content_block_stop", "\"index\":" + index);
    }

    private String event(String type, String fields) {
        String data = "{\"type\":\"" + type + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
        return "event: " + type + "\ndata: " + data.replace("\r", "").replace("\n", "") + "\n\n";
    }

    private JsonNode captured() throws InterruptedException {
        JsonNode request = requests.poll(3, TimeUnit.SECONDS);
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
