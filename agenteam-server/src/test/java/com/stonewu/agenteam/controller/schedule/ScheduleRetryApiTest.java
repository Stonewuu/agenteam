package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ScheduleRetryTestSupport;
import com.stonewu.agenteam.mapper.execution.RunAttemptSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.tool.ToolCallFixtureMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.RunAttemptRow;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.PluginTestServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型故障与只读请求验证自动尝试、独立输出、停止和一次计数。
 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduleRetryApiTest extends ScheduleRetryTestSupport {



    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final AtomicInteger READS = new AtomicInteger();

    private static final HttpServer WEB = web();

    private static final PluginTestServer REMOTE = new PluginTestServer();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", () -> origin() + "," + REMOTE.origin());
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        WEB.stop(0);
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 120})
    void twoTransientFailuresUseThreeIndependentOutputsAndOnlyOneQuotaEntry(int timeoutSeconds) throws Exception {
        verifyTransientRetryCounts(timeoutSeconds, () -> {
            // 社区只检查真实重试和一次计数，商业上限变更由商业用例覆盖。
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 422})
    void authenticationAndParameterErrorsAreNeverAutomaticallyRetried(int status) throws Exception {
        allowModelCalls = true;
        modelResponse = exchange -> error(exchange, status);
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("failed"));
        }
        assertEquals(1, count("run_attempt"));
        assertEquals(callsBefore + 1, modelCalls.get());
        assertEquals(1, used());
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getNextAttemptAt).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> (fixtureRecord.getNextAttemptAt() == null ? null : Timestamp.from(fixtureRecord.getNextAttemptAt()))).toList()));
    }

    @Test
    void stoppingDuringRetryWaitDoesNotSendTheNextRequestOrRefundConsumedQuota() throws Exception {
        allowModelCalls = true;
        modelResponse = exchange -> error(exchange, 503);
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        attempt(run, 2);
        write(base() + "/runs/" + run + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
        }
        assertEquals("cancelled", state(run));
        assertEquals(callsBefore + 1, modelCalls.get());
        assertEquals(1, used());
        assertEquals(0, reserved());
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getNextAttemptAt).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> (fixtureRecord.getNextAttemptAt() == null ? null : Timestamp.from(fixtureRecord.getNextAttemptAt()))).toList()));
        assertEquals(List.of("failed", "cancelled"), databaseAccess.mapper(RunAttemptSqlMapper.class).selectList(new LambdaQueryWrapper<RunAttemptRow>().select(RunAttemptRow::getStatus).orderByAsc(RunAttemptRow::getAttemptNo).eq(RunAttemptRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getActiveOccurrenceId).eq(ScheduledTaskRow::getId, (accepted.path("scheduleId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getActiveOccurrenceId()).toList()));
    }

    @Test
    void persistentTemporaryFailureStopsAtTheConfiguredRetryLimitAndSendsOnlyOneFailureNotice() throws Exception {
        allowModelCalls = true;
        modelResponse = exchange -> error(exchange, 503);
        var accepted = submitSchedule(1);
        String run = accepted.path("runId").asText();
        attempt(run, 2);
        ready(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("failed"));
            worker.poll();
        }
        assertEquals(callsBefore + 2, modelCalls.get());
        assertEquals(2, count("run_attempt"));
        assertEquals(1, used());
        assertEquals(0, reserved());
        assertEquals(List.of("failed", "failed"), databaseAccess.mapper(RunAttemptSqlMapper.class).selectList(new LambdaQueryWrapper<RunAttemptRow>().select(RunAttemptRow::getStatus).orderByAsc(RunAttemptRow::getAttemptNo).eq(RunAttemptRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification"))));
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getActiveOccurrenceId).eq(ScheduledTaskRow::getId, (accepted.path("scheduleId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getActiveOccurrenceId()).toList()));
    }

    @Test
    void failedPartialOutputIsPreservedAndTheOldWorkerCannotWriteIntoANewAttempt() throws Exception {
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        var lease = lifecycle.claim("first-attempt-worker").orElseThrow();
        var first = lifecycle.start(lease).orElseThrow();
        lifecycle.beforeExternalStep(lease);
        var block = new ContentBlock(UUID.randomUUID().toString(), "text", null, 1, "1", "已保存的部分结果", "running", null, null, null, null, null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.replace(block)));
        lifecycle.rejectClaim(lease, "MODEL_TEMPORARY_FAILURE", "模型连接暂时失败。");
        var snapshot = snapshot(accepted);
        assertEquals("failed", snapshot.at("/messages/1/status").asText());
        assertEquals("已保存的部分结果", snapshot.at("/messages/1/content").asText());
        assertNotEquals(first.outputMessageId(), snapshot.at("/activeRun/outputMessageId").asText());
        assertThrows(ExecutionStoppedException.class, () -> messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.replace(block.update("错误的晚到内容", "completed")))));
        lifecycle.finish(lease, "completed", null, null);
        assertEquals("queued", state(run));
        assertEquals(2, attemptNo(run));
        assertTrue(lifecycle.claim("too-early-worker").isEmpty());
        assertEquals("已保存的部分结果", snapshot(accepted).at("/messages/1/content").asText());
    }

    @Test
    void retryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptState() throws Exception {
        plugin();
        allowModelCalls = true;
        int before = READS.get();
        var turn = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            int iteration = turn.incrementAndGet();
            if (iteration == 1 || iteration == 3) {
                assertFalse(request.toString().contains("第一个尝试的读取内容"));
                String alias = modelTool(request, "read_url");
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "same-provider-call", "type", "function", "function", Map.of("name", alias, "arguments", json.writeValueAsString(Map.of("url", origin() + "/read")))))), "tool_calls");
            } else if (iteration == 2) {
                error(exchange, 503);
            } else {
                frame(exchange, Map.of("content", "再次读取后的结果"), "stop");
            }
        };
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        attempt(run, 2);
        ready(run);
        finish(run);
        assertEquals(before + 2, READS.get());
        assertEquals(2, count("tool_call"));
        assertEquals(1, used());
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject(run)));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject16(run)));
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject17(run)));
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallFixtureMapper.class).scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject18(run)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void completedWritesAndExplicitRejectionsPreventWholeRunRetryAfterLaterModelFailure(String decision) throws Exception {
        var writes = new AtomicInteger();
        REMOTE.reset();
        REMOTE.tools = json.valueToTree(List.of(Map.of("name", "save_item", "description", "保存验收记录", "inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text")), "annotations", Map.of("readOnlyHint", false))));
        REMOTE.toolResult = value -> {
            writes.incrementAndGet();
            return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "记录已经保存")), "isError", false));
        };
        var config = json.createObjectNode().put("icon", "Box").put("color", "purple").put("pluginType", "mcp").putNull("builtinCode").put("transport", "streamable_http").put("endpoint", REMOTE.endpoint()).putNull("credentialId").put("timeoutSeconds", 10);
        config.set("enabledToolNames", json.valueToTree(List.of("save_item")));
        String plugin = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "写入验收", "description", "保存本地验收记录", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/plugins/" + plugin + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        String pluginVersion = data(change(HttpMethod.POST, base() + "/resources/" + plugin + "/publish", Map.of("releaseNote", "写入验收"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> value.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion))));
        allowModelCalls = true;
        var modelTurn = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (modelTurn.incrementAndGet() == 1) {
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "save-once", "type", "function", "function", Map.of("name", modelTool(request, "save_item"), "arguments", json.writeValueAsString(Map.of("text", "只允许本次保存")))))), "tool_calls");
            } else {
                error(exchange, 503);
            }
        };
        var accepted = submitSchedule();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval"));
        }
        assertEquals(0, writes.get());
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", decision, "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("failed"));
        }
        assertEquals(decision.equals("approve") ? 1 : 0, writes.get());
        assertEquals(2, modelTurn.get());
        assertEquals(1, count("run_attempt"));
        assertEquals(1, used());
        assertEquals(0, reserved());
        assertEquals(1, count("tool_call"));
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getNextAttemptAt).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> (fixtureRecord.getNextAttemptAt() == null ? null : Timestamp.from(fixtureRecord.getNextAttemptAt()))).toList()));
    }

    private void plugin() throws Exception {
        var config = json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "只读资料", "description", "本地验收资料", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String fixed = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "只读尝试"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(configure -> configure.set("pluginVersionIds", json.valueToTree(List.of(fixed))));
    }

    private static String origin() {
        return "http://127.0.0.1:" + WEB.getAddress().getPort();
    }

    private static HttpServer web() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/read", exchange -> {
                READS.incrementAndGet();
                byte[] content = "第一个尝试的读取内容".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动本地只读验收服务", failure);
        }
    }
}
