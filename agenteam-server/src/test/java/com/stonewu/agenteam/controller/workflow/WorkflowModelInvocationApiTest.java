package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunApprovalSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunStepSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRow;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型通过原生外部工具调用工作流，重建工作进程后继续原请求而不是新建一次对话。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkflowModelInvocationApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p06-model-workflow-workspace");
        registry.add("execution.state-root", () -> "target/p06-model-workflow-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 120})
    void modelReceivesTheRealWorkflowResultAndContinuesInTheSameRun(int timeoutSeconds) throws Exception {
        bind(graph(List.of(start(), node("work", "transform", Map.of("fields", List.of(Map.of("target", "answer", "template", "流程返回：${input.text}")))), end(Map.of("text", "${steps.work.output.answer}"))), List.of(edge("start", "work"), edge("work", "end"))));
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", timeoutSeconds));
        var resumed = new AtomicReference<JsonNode>();
        var turns = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (turns.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(call(request, "flow-request", "原始流程参数", 0))), "tool_calls");
            } else {
                resumed.set(request);
                frame(exchange, Map.of("content", "已根据真实工作流结果完成回复。"), "stop");
            }
        };
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(callsBefore + 2, modelCalls.get());
        assertNotNull(resumed.get());
        assertTrue(resumed.get().toString().contains("流程返回：原始流程参数"));
        assertTrue(resumed.get().findValuesAsText("tool_call_id").contains("flow-request"));
        assertEquals(0, count("tool_call"));
        assertEquals(0, count("run_approval"));
        var snapshot = snapshot(accepted);
        schemas.validate("ConversationSnapshot", snapshot);
        assertEquals("已根据真实工作流结果完成回复。", snapshot.at("/messages/1/content").asText());
        for (var block : snapshot.at("/messages/1/blocks")) {
            assertFalse(block.path("type").asText().equals("tool"), "调用工作流不能生成伪造的插件或研究工具块");
        }
        validateEvents(accepted);
    }

    @Test
    void multipleWorkflowConfirmationsReleaseTheWorkerAndReturnBothOriginalToolResultsAfterRestart() throws Exception {
        bind(graph(List.of(start(), node("approval", "approval", Map.of("title", "确认：${input.text}", "description", "核对本次输入", "inputMapping", Map.of("text", "${input.text}"))), node("yes", "transform", Map.of("fields", List.of())), node("no", "transform", Map.of("fields", List.of())), end(Map.of("text", "${input.text}", "decision", "${steps.approval.output.decision}"))), List.of(edge("start", "approval"), edge("approval", "yes", "approve"), edge("approval", "no", "reject"), edge("yes", "end"), edge("no", "end"))));
        var turns = new AtomicInteger();
        var resumed = new AtomicReference<JsonNode>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (turns.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(call(request, "flow-first", "甲事项", 0), call(request, "flow-second", "乙事项", 1))), "tool_calls");
            } else {
                resumed.set(request);
                frame(exchange, Map.of("content", "已按两项流程的实际决定完成整理。"), "stop");
            }
        };
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> statusOf(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", statusOf(run), () -> diagnostic(run));
        assertEquals(callsBefore + 1, modelCalls.get());
        assertEquals(0, count("tool_call"));
        var confirmations = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(2, confirmations.size());
        for (var confirmation : confirmations) {
            String decision = confirmation.at("/summary/title").asText().contains("甲") ? "approve" : "reject";
            change(HttpMethod.POST, base() + "/approvals/" + confirmation.path("id").asText() + "/decision", Map.of("decision", decision, "requestHash", confirmation.path("requestHash").asText()), confirmation.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        }
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(callsBefore + 2, modelCalls.get());
        assertEquals(2, count("run_approval"));
        assertNotNull(resumed.get());
        assertTrue(resumed.get().findValuesAsText("tool_call_id").containsAll(List.of("flow-first", "flow-second")));
        assertTrue(resumed.get().toString().contains("approve"));
        assertTrue(resumed.get().toString().contains("reject"));
        assertEquals("已按两项流程的实际决定完成整理。", snapshot(accepted).at("/messages/1/content").asText());
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getRunId, (run)).eq(RunStepRow::getKind, "workflow"))));
        validateEvents(accepted);
    }

    @Test
    void threeLargeWorkflowRepliesShareTheByteBudgetAndRemainReadable() throws Exception {
        String body = "流程完整资料".repeat(3000) + "流程末尾答案是九十七";
        bind(graph(List.of(start(), end(Map.of("text", body))), List.of(edge("start", "end"))));
        var turns = new AtomicInteger();
        var path = new AtomicReference<String>();
        var read = new AtomicReference<String>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            switch (turns.getAndIncrement()) {
                case 0 ->
                    frame(exchange, Map.of("tool_calls", List.of(call(request, "big-first", "甲", 0), call(request, "big-second", "乙", 1), call(request, "big-third", "丙", 2))), "tool_calls");
                case 1 -> {
                    int total = 0;
                    for (String id : List.of("big-first", "big-second", "big-third")) {
                        String result = resultText(request, id);
                        int bytes = result.getBytes(StandardCharsets.UTF_8).length;
                        assertTrue(bytes <= 32 * 1024 * 2 / 3);
                        total += bytes;
                        String saved = json.readTree(result).path("path").asText();
                        assertTrue(saved.startsWith("tool-results/"));
                        path.set(saved);
                    }
                    assertTrue(total <= 64 * 1024);
                    fileTool(exchange, "flow-find", "grep_files", Map.of("path", path.get(), "pattern", "末尾答案", "before", 0, "after", 0));
                }
                case 2 -> {
                    var match = json.readTree(resultText(request, "flow-find")).path("matches").get(0);
                    fileTool(exchange, "flow-read", "read_file", Map.of("path", path.get(), "cursor", match.path("readCursor").asText()));
                }
                default -> {
                    read.set(json.readTree(resultText(request, "flow-read")).path("content").asText());
                    frame(exchange, Map.of("content", "九十七"), "stop");
                }
            }
        };
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertTrue(read.get().contains("流程末尾答案是九十七"));
        assertEquals(5, count("tool_call"));
        assertEquals(0, count("run_approval"));
    }

    private void fileTool(HttpExchange exchange, String id, String name, Map<String, Object> arguments) throws IOException {
        frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function",
            "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
    }

    private String resultText(JsonNode request, String id) {
        for (var message : request.path("messages")) {
            if (id.equals(message.path("tool_call_id").asText())) {
                var content = message.path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
                for (var item : content) {
                    if (item.path("text").isTextual()) {
                        return item.path("text").asText();
                    }
                }
            }
        }
        throw new IllegalStateException("模型请求中没有对应的流程或文件结果");
    }

    @Test
    void cancelledWorkflowDoesNotResumeWhenTheUserContinuesTheConversation() throws Exception {
        bind(graph(List.of(start(), node("approval", "approval", Map.of("title", "等待本次决定", "description", "核对内容", "inputMapping", Map.of())), node("yes", "transform", Map.of("fields", List.of())), node("no", "transform", Map.of("fields", List.of())), end(Map.of("text", "原流程结束"))), List.of(edge("start", "approval"), edge("approval", "yes", "approve"), edge("approval", "no", "reject"), edge("yes", "end"), edge("no", "end"))));
        var turns = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (turns.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(call(request, "stopped-flow", "待停止事项", 0))), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "继续处理新的问题。"), "stop");
            }
        };
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> statusOf(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", statusOf(run));
        write(base() + "/runs/" + run + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertEquals("cancelled", statusOf(run));
        var next = data(write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages", input("继续新的问题"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(next.path("runId").asText()));
        }
        assertCompleted(next.path("runId").asText());
        assertEquals(callsBefore + 2, modelCalls.get());
        assertEquals(1, count("run_approval"));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getRunId, (next.path("runId").asText())).eq(RunStepRow::getKind, "workflow"))));
        assertEquals("revoked", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunApprovalSqlMapper.class).selectList(new LambdaQueryWrapper<RunApprovalRow>().select(RunApprovalRow::getStatus).eq(RunApprovalRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
    }

    private void bind(JsonNode graph) throws Exception {
        String id = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "模型调用的工作流", "description", "核对真实调用及恢复", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String version = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "模型调用验收"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(config -> {
            config.withArray("workflowVersionIds").add(version);
            config.put("maxSteps", 50);
        });
    }

    private JsonNode submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private String statusOf(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(statusOf(run));
    }

    private String diagnostic(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString();
    }

    private void assertCompleted(String run) {
        assertEquals("completed", statusOf(run), () -> diagnostic(run));
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private void validateEvents(JsonNode accepted) {
        long sequence = 0;
        for (var event : events.after(enterprise, accepted.path("conversationId").asText(), 0, 1000)) {
            assertEquals(Long.toString(++sequence), event.sequence());
            schemas.validate("ExecutionEvent", json.valueToTree(event));
        }
    }

    private Map<String, Object> call(JsonNode request, String id, String input, int index) throws IOException {
        String name = "";
        for (var tool : request.path("tools")) {
            if (tool.at("/function/name").asText().startsWith("run_workflow")) {
                name = tool.at("/function/name").asText();
            }
        }
        assertFalse(name.isBlank());
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(Map.of("text", input))));
    }

    private JsonNode graph(List<JsonNode> nodes, List<JsonNode> edges) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", edges));
    }

    private JsonNode start() {
        return node("start", "start", Map.of("inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text"))));
    }

    private JsonNode end(Map<String, Object> output) {
        return node("end", "end", Map.of("outputMapping", output));
    }

    private JsonNode node(String id, String type, Object config) {
        return json.valueToTree(Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config));
    }

    private JsonNode edge(String from, String to) {
        return edge(from, to, "default");
    }

    private JsonNode edge(String from, String to, String branch) {
        return json.valueToTree(Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", branch));
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var value = Map.of("id", UUID.randomUUID().toString(), "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(value) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
