package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.plugin.BuiltinPluginAdapter;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.QueryableToolTestServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实写入与查询服务验证原操作编号、恢复、未知结果和重复请求上限。
 */
@Import({SharedEnterpriseTestEdition.class, QueryableToolRecoveryApiTest.Configuration.class})
class QueryableToolRecoveryApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final QueryableToolTestServer REMOTE = new QueryableToolTestServer();

    private String plugin;

    @TestConfiguration
    static class Configuration {

        @Bean
        BuiltinPluginAdapter queryAdapter(RestrictedHttpClient http, ResourceJson json) {
            return REMOTE.adapter(http, json);
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", REMOTE::origin);
        registry.add("execution.workspace-root", () -> "target/p04-query-workspace");
        registry.add("execution.state-root", () -> "target/p04-query-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @Test
    void timedOutWriteIsCompletedByQueryingTheSameOperationWithoutResending() throws Exception {
        var accepted = waiting("timeout", 1);
        String run = accepted.path("runId").asText();
        approve(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
        assertEquals(1, REMOTE.writes.get());
        assertEquals(1, REMOTE.queries.get());
        assertCall(run, "succeeded", 1, 1);
        assertEquals(REMOTE.operationIds, REMOTE.queryIds);
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertTrue(snapshot.toString().contains("查询恢复使用的原始正文"));
    }

    @Test
    void unknownQueryResultCannotCauseAnotherWriteOrManualRetry() throws Exception {
        var accepted = waiting("unknown", 10);
        String run = accepted.path("runId").asText();
        approve(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("failed", state(run));
        assertCall(run, "unknown", 1, 1);
        write(base() + "/runs/" + run + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        try (var restarted = new RunWorker(lifecycle, tasks)) {
            restarted.poll();
        }
        assertEquals(1, REMOTE.writes.get());
        assertEquals(1, REMOTE.queries.get());
    }

    @Test
    void authoritativeNotExecutedResultAllowsOneRetryWithOriginalArgumentsAndOperation() throws Exception {
        var accepted = waiting("once", 10);
        String run = accepted.path("runId").asText();
        approve(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
        assertCall(run, "succeeded", 2, 1);
        assertEquals(2, REMOTE.writes.get());
        assertEquals(1, REMOTE.saved.size());
        assertEquals(REMOTE.operationIds.get(0), REMOTE.operationIds.get(1));
        assertEquals(REMOTE.received.get(0), REMOTE.received.get(1));
    }

    @Test
    void repeatedNotExecutedRepliesDoNotCreateAnUnboundedRetryLoop() throws Exception {
        var accepted = waiting("never", 10);
        String run = accepted.path("runId").asText();
        approve(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCall(run, "failed", 2, 2);
        assertEquals(2, REMOTE.writes.get());
        assertEquals(2, REMOTE.queries.get());
        assertEquals(0, REMOTE.saved.size());
    }

    @Test
    void replacementWorkerQueriesPendingWriteAndLateOriginalResultCannotChangeIt() throws Exception {
        var accepted = waiting("lost", 10);
        String run = accepted.path("runId").asText();
        REMOTE.holdFirstQuery = true;
        approve(run);
        var old = new RunWorker(lifecycle, tasks);
        try (var replacement = new RunWorker(lifecycle, tasks)) {
            old.poll();
            assertTrue(REMOTE.queryStarted.await(5, TimeUnit.SECONDS));
            databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, ("run:" + run)).set(BackgroundJobRow::getLeaseUntil, (Timestamp.from(Instant.now().minusSeconds(1)))));
            replacement.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run), () -> diagnostic(run));
            assertCall(run, "succeeded", 1, 2);
            assertEquals(1, REMOTE.writes.get());
            assertTrue(REMOTE.queryIds.stream().allMatch(value -> value.equals(REMOTE.operationIds.getFirst())));
        } finally {
            REMOTE.releaseQuery.countDown();
            old.close();
        }
        assertEquals("completed", state(run));
        assertEquals(1, REMOTE.saved.size());
    }

    @Test
    void disablingSourceBeforeQueryPreventsBothQueryAndFurtherWrite() throws Exception {
        var accepted = waiting("lost", 10);
        String run = accepted.path("runId").asText();
        var ready = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        REMOTE.beforeQuery = () -> {
            ready.countDown();
            try {
                assertTrue(proceed.await(8, TimeUnit.SECONDS));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        };
        approve(run);
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            change(HttpMethod.PATCH, base() + "/resources/" + plugin + "/status", Map.of("status", "disabled"), "2", UUID.randomUUID().toString()).andExpect(status().isOk());
            proceed.countDown();
            worker.poll();
            await(() -> terminal(run));
            assertCall(run, "unknown", 1, 0);
            assertEquals(1, REMOTE.writes.get());
            assertEquals(0, REMOTE.queries.get());
        } finally {
            proceed.countDown();
        }
    }

    private JsonNode waiting(String mode, int timeout) throws Exception {
        REMOTE.reset(mode);
        var config = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"builtin","builtinCode":"query_test","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":10,"enabledToolNames":["write_record"]}
            """);
        config.put("timeoutSeconds", timeout);
        plugin = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "可查询记录工具", "description", "核实实际操作结果", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String version = data(change(HttpMethod.POST, base() + "/resources/" + plugin + "/publish", Map.of("releaseNote", "发布可查询工具"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> {
            value.put("maxSteps", 0).put("timeoutSeconds", 0);
            value.set("pluginVersionIds", json.valueToTree(List.of(version)));
        });
        var iteration = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "queryable-call", "type", "function", "function", Map.of("name", modelTool(request, "write_record"), "arguments", "{\"text\":\"查询恢复使用的原始正文\"}")))), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已根据目标系统的结果完成说明。"), "stop");
            }
        };
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", state(run), () -> diagnostic(run));
        assertEquals(0, REMOTE.writes.get());
        return accepted;
    }

    private void approve(String run) throws Exception {
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    private void assertCall(String run, String status, int attempts, int queries) {
        var call = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectMaps(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getStatus, ToolCallRow::getAttemptCount, ToolCallRow::getQueryCount, ToolCallRow::getLastQueryAt).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("status", fixtureValues.get("status"));
            fixtureRow.put("attempt_count", fixtureValues.get("attempt_count"));
            fixtureRow.put("query_count", fixtureValues.get("query_count"));
            fixtureRow.put("last_query_at", fixtureValues.get("last_query_at"));
            return fixtureRow;
        }).toList());
        assertEquals(status, call.get("status"));
        assertEquals(attempts, ((Number) call.get("attempt_count")).intValue());
        assertEquals(queries, ((Number) call.get("query_count")).intValue());
        if (queries > 0) {
            assertNotNull(call.get("last_query_at"));
        }
    }

    private String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    private String diagnostic(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus, AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("status", fixtureValues.get("status"));
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString();
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "query-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
