package com.stonewu.agenteam.support.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 资料与子任务工具测试共用模型响应、执行完成和对话结果读取。 */
public abstract class ModelToolApiTestSupport extends ExecutionApiTestSupport {
    protected JsonNode submit(String question) throws Exception {
        allowModelCalls = true;
        return data(write(base() + "/conversations", Map.of("agentId", agent, "input", input(question)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    protected void finish(JsonNode accepted) {
        var lease = lifecycle.claim("source-tools-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(accepted.path("runId").asText(), run.id());
        try (var task = adapter.create(run, lease)) {
            task.completion().block(Duration.ofSeconds(25));
            task.saveCheckpoint();
            lifecycle.finish(lease, "completed", null, null);
        }
    }

    protected JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected Map<String, Object> tool(int index, String id, String name, Object arguments) throws IOException {
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
    }

    protected void toolCalls(HttpExchange exchange, List<Map<String, Object>> calls) throws IOException {
        frame(exchange, Map.of("tool_calls", calls), "tool_calls");
    }

    protected void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        String chunk = json.writeValueAsString(Map.of("id", "source-stream", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish))));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
