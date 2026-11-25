package com.stonewu.agenteam.controller.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.system.RuntimeSettings;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunCheckpointMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.execution.ModelToolApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过管理接口修改后，验证任务接收、同会话排队及等待超时使用新设置。 */
@Import(SharedEnterpriseTestEdition.class)
class RuntimeSettingsApiTest extends ModelToolApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private RuntimeSettings settings;
    @Autowired
    private RuntimeSettingsStore runtimeStore;
    @Autowired
    private RunMapper runs;
    @Autowired
    private RunCheckpointMapper checkpoints;
    @Autowired
    private ToolCallMapper tools;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.user-workspaces-root", () -> TestOutputPaths.path("runtime-settings-api/workspaces").toString());
    }

    @AfterEach
    void resetSettings() {
        var saved = runtimeStore.current();
        if (saved != null) {
            runtimeStore.save(saved.revision(), Map.of());
        }
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void unlimitedAdmissionAndQueueTimeApplyToSavedTasks() throws Exception {
        String endpoint = "/api/v1/system/runtime-settings";
        var current = data(mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var updated = data(change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of(
            "workerConcurrent", 1000, "enterpriseActiveRuns", -1, "userActiveRuns", -1,
            "conversationQueuedMessages", -1, "queueTimeoutSeconds", -1)), current.path("revision").asText(), UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertEquals(1000, updated.path("values").path("workerConcurrent").asInt());
        assertEquals(-1, updated.path("values").path("queueTimeoutSeconds").asInt());
        for (int index = 0; index < 22; index++) {
            write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        }
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        for (int index = 0; index < 22; index++) {
            write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages?queue=true",
                input("等待消息 " + index), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        }
        String run = accepted.path("runId").asText();
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>()
            .eq(AgentRunRow::getId, run).set(AgentRunRow::getQueuedAt, Instant.EPOCH));
        assertTrue(lifecycle.queueTimeouts().isEmpty());
        assertFalse(settings.queueExpired(Instant.EPOCH, Instant.now()));
        assertEquals(callsBefore, modelCalls.get());
    }

    @ParameterizedTest
    @CsvSource({"0,503,1", "2,503,3", "2,401,1"})
    void ordinaryConversationsUseTheSavedRetryCountOnlyForTransientFailures(int retries, int httpStatus, int expected) throws Exception {
        String endpoint = "/api/v1/system/runtime-settings";
        var current = data(mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, current.at("/values/conversationModelRetries").asInt());
        assertEquals(2, current.at("/bounds/conversationModelRetries/maximum").asInt());
        change(HttpMethod.PUT, endpoint + "/model", Map.of("values", Map.of("conversationModelRetries", retries)),
            current.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        modelResponse = exchange -> {
            exchange.sendResponseHeaders(httpStatus, -1);
            exchange.close();
        };
        var accepted = submit("检查模型回复");
        String runId = accepted.path("runId").asText();
        assertEquals(1 + retries, runs.find(enterprise, runId, false).orElseThrow().maxAttempts());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            for (int index = 1; index <= expected; index++) {
                int attempt = index;
                await(() -> {
                    worker.poll();
                    var run = runs.find(enterprise, runId, false).orElseThrow();
                    return run.status().equals("failed") || run.currentAttemptNo() > attempt;
                });
            }
        }
        var finished = runs.find(enterprise, runId, false).orElseThrow();
        assertEquals("failed", finished.status());
        assertEquals(expected, finished.currentAttemptNo());
        assertEquals(expected, modelCalls.get() - callsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume", "missing-checkpoint", "cancel-before-retry"})
    void interruptedReplyKeepsItsTextAndResumesAfterTheCompletedWrite(String scenario) throws Exception {
        String fragment = "回答写到一半";
        String answer = "文件已保存。";
        var requests = new CopyOnWriteArrayList<JsonNode>();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            requests.add(request);
            switch (requests.size()) {
                case 1 -> toolCalls(exchange, List.of(tool(0, "saved-write", modelTool(request, "write_file"),
                    Map.of("path", "retry-result.txt", "content", "已保存"))));
                case 2 -> {
                    var chunk = Map.of("id", "partial", "object", "chat.completion.chunk", "created", 1, "model", "test-model",
                        "choices", List.of(Map.of("index", 0, "delta", Map.of("content", fragment))));
                    byte[] body = ("data: " + json.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length + 32);
                    try (exchange) {
                        exchange.getResponseBody().write(body);
                        exchange.getResponseBody().flush();
                    }
                }
                default -> frame(exchange, Map.of("content", answer), "stop");
            }
        };
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("保存文件再回复"),
            "approvalPolicy", "full_access"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String runId = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, runId, false).orElseThrow().currentAttemptNo() == 2);
        }
        var queued = snapshot(accepted);
        var first = queued.at("/messages/1");
        assertEquals("failed", first.path("status").asText());
        assertTrue(first.toString().contains(fragment));
        assertNotEquals(first.path("id"), queued.at("/messages/2/id"));
        assertEquals(accepted.path("inputMessageId"), first.path("inputMessageId"));
        assertEquals(accepted.path("inputMessageId"), queued.at("/messages/2/inputMessageId"));
        if (scenario.equals("cancel-before-retry")) {
            String path = base() + "/runs/" + runId;
            write(path + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            assertEquals("cancelled", runs.find(enterprise, runId, false).orElseThrow().status());
            assertEquals(2, requests.size());
            var manual = data(write(path + "/retry", null, UUID.randomUUID().toString())
                .andExpect(status().isAccepted()).andReturn());
            finish(manual);
            assertEquals(requests.get(1).path("messages"), requests.get(2).path("messages"));
            assertTrue(tools.forRun(enterprise, manual.path("runId").asText()).isEmpty());
            return;
        }
        if (scenario.equals("missing-checkpoint")) {
            checkpoints.remove(enterprise, runId);
        }
        try (var nextWorker = new RunWorker(lifecycle, tasks)) {
            await(() -> {
                nextWorker.poll();
                return runs.find(enterprise, runId, false).orElseThrow().terminal();
            });
        }
        var finished = runs.find(enterprise, runId, false).orElseThrow();
        assertEquals(1, tools.forRun(enterprise, runId).size());
        if (scenario.equals("missing-checkpoint")) {
            assertEquals("EXECUTION_STATE_UNAVAILABLE", finished.errorCode());
            assertEquals(2, requests.size());
            return;
        }
        assertEquals("completed", finished.status());
        assertEquals(3, requests.size());
        assertEquals(requests.get(1).path("messages"), requests.get(2).path("messages"));
        assertTrue(requests.get(2).path("messages").toString().contains("saved-write"));
        assertFalse(requests.get(2).toString().contains(fragment));
        var messages = snapshot(accepted).path("messages");
        assertEquals(3, messages.size());
        assertEquals(first.path("blocks"), messages.get(1).path("blocks"));
        assertEquals(answer, messages.get(2).path("content").asText());
        assertEquals(2, messages.get(2).path("attemptNo").asInt());
    }

    @Test
    void savedLimitsControlNewAndAlreadyQueuedTasksWithoutRestart() throws Exception {
        String endpoint = "/api/v1/system/runtime-settings";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        var current = data(mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var revision = current.path("revision").asText();
        var updated = data(change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of(
            "workerConcurrent", 2, "enterpriseActiveRuns", 2, "userActiveRuns", 1,
            "conversationQueuedMessages", 1, "queueTimeoutSeconds", 60)), revision, UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertEquals(2, settings.value(RuntimeSetting.WORKER_CONCURRENT));
        change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of("workerConcurrent", 3)),
            revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.PUT, endpoint + "/model", Map.of("values", Map.of("toolReadKb", 999)),
            updated.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());

        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var denied = write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andReturn();
        assertTrue(denied.getResponse().getContentAsString().contains("CONCURRENCY_LIMIT"));
        String conversation = accepted.path("conversationId").asText();
        var waiting = data(write(base() + "/conversations/" + conversation + "/messages?queue=true", input("下一条消息"),
            UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals("waiting_turn", waiting.path("status").asText());
        var full = write(base() + "/conversations/" + conversation + "/messages?queue=true", input("再追加一条"),
            UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertTrue(full.getResponse().getContentAsString().contains("CONVERSATION_QUEUE_FULL"));

        String run = accepted.path("runId").asText();
        assertEquals(1, databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>()
            .eq(AgentRunRow::getId, run).set(AgentRunRow::getQueuedAt, Instant.now().minusSeconds(10))));
        change(HttpMethod.PUT, endpoint + "/execution", Map.of("values", Map.of("queueTimeoutSeconds", 5)),
            updated.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var lease = lifecycle.claim("runtime-settings-test").orElseThrow();
        assertEquals(run, lease.runId());
        assertTrue(lifecycle.start(lease).isEmpty());
        assertEquals("EXECUTION_QUEUE_TIMEOUT", runs.find(enterprise, run, false).orElseThrow().errorCode());
        assertEquals(callsBefore, modelCalls.get());
    }
}
