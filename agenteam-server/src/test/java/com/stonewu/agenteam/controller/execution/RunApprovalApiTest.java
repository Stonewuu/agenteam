package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunApprovalSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunCheckpointSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.permission.SysRoleTableMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRow;
import com.stonewu.agenteam.model.execution.entity.RunCheckpointRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.RunProcessTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用原生确认事件和新的工作进程验证批准、拒绝、过期及外部请求数量。
 */
@Import(SharedEnterpriseTestEdition.class)
class RunApprovalApiTest extends RunApprovalApiTestSupport {

    @Test
    void nativeApprovalSurvivesWorkerReplacementAndUsesExactlyTheOriginalArgumentsOnce() throws Exception {
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getTaskCompletionNotifications, false));
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var approval = approvals(run).get(0);
        schemas.validate("Approval", approval);
        for (var candidate : notifications.candidates().stream().filter(value -> value.enterprise().equals(enterprise)).toList()) {
            notifications.deliver(candidate);
            notifications.deliver(candidate);
        }
        assertEquals(1, count("notification"));
        var notice = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectMaps(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getCategory, NotificationRow::getTargetType, NotificationRow::getTargetId, NotificationRow::getBody).eq(NotificationRow::getEnterpriseId, (enterprise))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("category", fixtureValues.get("category"));
            fixtureRow.put("target_type", fixtureValues.get("target_type"));
            fixtureRow.put("target_id", fixtureValues.get("target_id"));
            fixtureRow.put("body", fixtureValues.get("body"));
            return fixtureRow;
        }).toList());
        assertEquals("approval", notice.get("category"));
        assertEquals("conversation", notice.get("target_type"));
        assertEquals(accepted.path("conversationId").asText(), notice.get("target_id"));
        assertFalse(notice.get("body").toString().contains("原始固定正文"));
        assertFalse(notice.get("body").toString().contains("original-test-password"));
        assertTrue(approval.at("/summary/content").asText().contains("原始固定正文"));
        assertFalse(approval.toString().contains("original-test-password"));
        assertEquals(0, writes.get());
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, ("run:" + run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
        assertNotNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunCheckpointSqlMapper.class).selectList(new LambdaQueryWrapper<RunCheckpointRow>().select(RunCheckpointRow::getFrameworkStateKey).eq(RunCheckpointRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getFrameworkStateKey()).toList()));
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getId, (run)).set(AgentRunRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(3600)))));
        String key = UUID.randomUUID().toString();
        decision(approval, "approve", key).andExpect(status().isOk());
        decision(approval, "approve", key).andExpect(status().isOk());
        decision(approval, "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        decision(approval, "reject", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals("queued", state(run));
        try (var resumed = new RunWorker(lifecycle, tasks)) {
            resumed.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run), () -> diagnostic(run));
            assertEquals(1, writes.get());
            assertEquals("原始固定正文", received.get().path("text").asText());
            assertEquals("original-test-password", received.get().path("password").asText());
            assertEquals("one@example.test", received.get().path("recipient").asText());
            assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
            assertEquals(1, count("run_attempt"));
            assertEquals(1, count("tool_call"));
            assertEquals(1, count("run_approval"));
            assertEquals(callsBefore + 2, modelCalls.get());
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertFalse(snapshot.toString().contains("original-test-password"));
            assertFalse(snapshot.toString().contains("platform_"));
            assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification"))));
            String conversationCsv = exportCsv(base() + "/conversations/" + accepted.path("conversationId").asText() + "/export", null);
            assertTrue(conversationCsv.contains("原始固定正文"));
            assertFalse(conversationCsv.contains("original-test-password"));
            String logCsv = exportCsv(base() + "/tool-calls/export", Map.of("from", Instant.now().minusSeconds(3600).toString(), "to", Instant.now().plusSeconds(10).toString(), "status", "succeeded", "source", "interactive"));
            assertTrue(logCsv.contains("succeeded"));
            assertFalse(logCsv.contains("原始固定正文"));
            assertFalse(logCsv.contains("original-test-password"));
        }
    }

    @Test
    void concurrentApprovalAndRefusalProduceOneDecisionAndOnlyTheApprovedWrite() throws Exception {
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var approval = approvals(run).get(0);
        var ready = new CountDownLatch(2);
        var begin = new CountDownLatch(1);
        try (var concurrent = Executors.newVirtualThreadPerTaskExecutor()) {
            var yes = concurrent.submit(() -> {
                ready.countDown();
                assertTrue(begin.await(5, TimeUnit.SECONDS));
                return decision(approval, "approve", UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            });
            var no = concurrent.submit(() -> {
                ready.countDown();
                assertTrue(begin.await(5, TimeUnit.SECONDS));
                return decision(approval, "reject", UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            begin.countDown();
            assertEquals(List.of(200, 409), List.of(yes.get(10, TimeUnit.SECONDS), no.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
        } finally {
            begin.countDown();
        }
        String outcome = approvals(run).get(0).path("status").asText();
        try (var resumed = new RunWorker(lifecycle, tasks)) {
            resumed.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run));
            assertEquals(outcome.equals("approved") ? 1 : 0, writes.get());
            assertEquals(1, count("run_approval"));
        }
    }

    @Test
    void approvalCannotResetAnExhaustedExecutionTimeBudget() throws Exception {
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        databaseAccess.mapper(RunSqlMapper.class).update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getId, (run)).set(AgentRunRow::getActiveMillis, 120000));
        int calls = modelCalls.get();
        decision(approvals(run).get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var resumed = new RunWorker(lifecycle, tasks)) {
            resumed.poll();
            await(() -> terminal(run));
            assertEquals("failed", state(run));
            assertEquals("EXECUTION_TIMEOUT", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            assertEquals(0, writes.get());
            assertEquals(calls, modelCalls.get());
            assertEquals(1, count("run_attempt"));
        }
    }

    @Test
    void refusingThenAskingWithTheSameParametersDoesNotCreateAnotherApprovalOrWrite() throws Exception {
        plugin();
        repeatProposal = true;
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var approval = approvals(run).get(0);
        decision(approval, "reject", UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var resumed = new RunWorker(lifecycle, tasks)) {
            resumed.poll();
            await(() -> terminal(run));
            assertEquals(0, writes.get());
            assertEquals(1, count("run_approval"));
            assertEquals("completed", state(run), () -> diagnostic(run));
            assertEquals(2, count("tool_call"));
            assertEquals(callsBefore + 3, modelCalls.get());
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            for (var block : snapshot.at("/messages/1/blocks")) {
                if (block.path("type").asText().equals("tool")) {
                    assertEquals("skipped", block.path("status").asText());
                    assertEquals("skipped", block.at("/tool/resultStatus").asText());
                    assertTrue(block.at("/tool/result").asText().contains("未发送请求"));
                }
            }
        }
    }

    @Test
    void aBatchWaitsForEveryDecisionAndSendsOnlyApprovedCalls() throws Exception {
        plugin();
        proposalCount = 2;
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var pending = approvals(run);
        assertEquals(2, pending.size());
        decision(pending.get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("waiting_approval", state(run));
        assertEquals(0, writes.get());
        decision(pending.get(1), "reject", UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run), () -> diagnostic(run));
            assertEquals(1, writes.get());
            assertEquals(2, count("run_approval"));
            assertEquals(2, count("tool_call"));
        }
    }

    @Test
    void changedRemoteStructureAfterApprovalPreventsDispatch() throws Exception {
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        REMOTE.tools = json.valueToTree(List.of(REMOTE.tool("Read_Item", 2), REMOTE.tool("read_item", 1)));
        decision(approvals(run).get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals(0, writes.get());
            assertEquals("PLUGIN_TOOL_CHANGED", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getErrorCode).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getAttemptCount).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
        }
    }

    @Test
    void timeoutAfterRemoteWriteIsUnknownAndCannotBeRetried() throws Exception {
        plugin(1);
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        REMOTE.toolResult = input -> {
            writes.incrementAndGet();
            received.set(input);
            try {
                Thread.sleep(1800);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return json.valueToTree(Map.of("isError", false, "content", List.of(Map.of("type", "text", "text", "迟到结果"))));
        };
        decision(approvals(run).get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals("failed", state(run));
            assertEquals(1, writes.get());
            assertEquals("TOOL_RESULT_UNKNOWN", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            assertEquals("unknown", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getStatus).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            write(base() + "/runs/" + run + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        }
        try (var restarted = new RunWorker(lifecycle, tasks)) {
            restarted.poll();
        }
        assertEquals(1, writes.get());
        assertEquals(1, count("agent_run"));
    }

    @Test
    void restartBetweenCompletedWriteAndNextModelKeepsTheResultWithoutSendingAgain() throws Exception {
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            var value = invocation.callRealMethod();
            RunRecord current = invocation.getArgument(0);
            if (current.id().equals(run) && Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, (run)).eq(ToolCallRow::getStatus, "succeeded"))) == 1 && once.compareAndSet(false, true)) {
                saved.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("没有收到结束恢复验收的信号");
                }
            }
            return value;
        }).when(checkpoints).captureLive(any(), any(), any(), any());
        decision(approvals(run).get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        var old = new RunWorker(lifecycle, tasks);
        try (var restarted = new RunWorker(lifecycle, tasks)) {
            old.poll();
            assertTrue(saved.await(12, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, ("run:" + run)).set(BackgroundJobRow::getLeaseUntil, (Timestamp.from(Instant.now().minusSeconds(1)))));
            restarted.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run), () -> diagnostic(run));
            assertEquals(1, writes.get());
            assertEquals(callsBefore + 2, modelCalls.get());
            assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getUsedSteps()).toList()));
        } finally {
            release.countDown();
            old.close();
        }
        assertEquals("completed", state(run));
    }

    @Test
    void anotherAdministratorCannotDecideButExplicitLogPermissionCanReadAuditedRedactedDetails() throws Exception {
        plugin();
        var accepted = waiting();
        String run = accepted.path("runId").asText();
        var approval = approvals(run).get(0);
        String call = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getId).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        String username = "approval-viewer-" + UUID.randomUUID();
        String password = "另一管理员独立验收口令2026!";
        var viewer = EnterpriseTestData.member(users, permissions, enterprise, username, password, "另一管理员", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var originalCookie = cookie;
        String originalCsrf = csrf;
        try {
            cookie = null;
            refreshCsrf();
            var login = write("/api/v1/auth/login", Map.of("identifier", username, "password", password), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
            cookie = login.getResponse().getCookie("SESSION");
            refreshCsrf();
            mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isNotFound());
            decision(approval, "approve", UUID.randomUUID().toString()).andExpect(status().isNotFound());
            var list = data(mvc.perform(get(base() + "/tool-calls").cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertEquals(1, list.path("items").size());
            schemas.validate("ToolCall", list.at("/items/0"));
            assertEquals("interactive", list.at("/items/0/source").asText());
            assertTrue(list.at("/items/0/conversationId").isNull());
            assertFalse(list.toString().contains("原始固定正文"));
            assertTrue(list.at("/items/0/canViewDetails").asBoolean());
            var details = data(mvc.perform(get(base() + "/tool-calls/" + call).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ToolCallDetails", details);
            assertTrue(details.toString().contains("原始固定正文"));
            assertTrue(details.at("/call/conversationId").isNull());
            assertFalse(details.toString().contains("original-test-password"));
            assertFalse(details.toString().contains("ciphertext"));
            assertEquals(1, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, (enterprise)).eq(AuditEventRow::getActorUserId, (viewer.id())).eq(AuditEventRow::getAction, "tool_log.details").eq(AuditEventRow::getObjectId, (call)))));
            String role = UUID.randomUUID().toString();
            permissions.insertRole(role, enterprise, "log-viewer", "仅查看日志摘要", "日志范围验收", DataScope.ENTERPRISE, false, Instant.now());
            permissions.replaceRolePermissions(enterprise, role, Set.of("tool_log.view"));
            permissions.replaceUserRoles(viewer.id(), enterprise, Set.of(role), Instant.now());
            assertFalse(data(mvc.perform(get(base() + "/tool-calls").cookie(cookie)).andExpect(status().isOk()).andReturn()).at("/items/0/canViewDetails").asBoolean());
            mvc.perform(get(base() + "/tool-calls/" + call).cookie(cookie)).andExpect(status().isForbidden());
            databaseAccess.mapper(SysRoleTableMapper.class).update(new LambdaUpdateWrapper<SysRoleRow>().eq(SysRoleRow::getEnterpriseId, (enterprise)).eq(SysRoleRow::getId, (role)).set(SysRoleRow::getDataScope, "own"));
            permissions.replaceRolePermissions(enterprise, role, Set.of("tool_log.view", "tool_log.details"));
            assertTrue(data(mvc.perform(get(base() + "/tool-calls").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
            mvc.perform(get(base() + "/tool-calls/" + call).cookie(cookie)).andExpect(status().isNotFound());
        } finally {
            cookie = originalCookie;
            csrf = originalCsrf;
        }
        assertEquals("waiting_approval", state(run));
        assertEquals(0, writes.get());
    }

    @Test
    void toolLogFiltersAndConversationLinksUseTheActualRunAndCurrentReader() throws Exception {
        plugin();
        var accepted = waiting();
        String call = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getId).eq(ToolCallRow::getRunId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        var selected = data(mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("source", "interactive").param("status", "waiting_approval").param("actorUserId", admin).param("from", Instant.now().minusSeconds(3600).toString()).param("to", Instant.now().plusSeconds(5).toString())).andExpect(status().isOk()).andReturn());
        assertEquals(1, selected.path("items").size());
        assertEquals(accepted.path("conversationId").asText(), selected.at("/items/0/conversationId").asText());
        assertTrue(data(mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("source", "scheduled")).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
        assertTrue(data(mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("status", "failed")).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
        assertTrue(data(mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("actorUserId", "another-user")).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
        mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("source", "made-up-source")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("status", "made-up-status")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(base() + "/tool-calls").cookie(cookie).param("from", Instant.now().minusSeconds(91 * 86400).toString())).andExpect(status().isUnprocessableEntity());
        EnterpriseTestData.member(users, permissions, enterprise, "log-admin-" + UUID.randomUUID(), "保留企业管理员的测试口令", "保留的管理员", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "only-own-logs", "只管理本人调用", "", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("tool_log.view", "tool_log.details"));
        permissions.replaceUserRoles(admin, enterprise, Set.of(role), Instant.now());
        try {
            var limited = data(mvc.perform(get(base() + "/tool-calls").cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertEquals(1, limited.path("items").size());
            assertTrue(limited.at("/items/0/conversationId").isNull());
            var details = data(mvc.perform(get(base() + "/tool-calls/" + call).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertTrue(details.at("/call/conversationId").isNull());
            assertFalse(details.toString().contains("original-test-password"));
            assertEquals(1, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, (enterprise)).eq(AuditEventRow::getAction, "tool_log.details"))));
        } finally {
            permissions.replaceUserRoles(admin, enterprise, Set.of(permissions.builtinRoleId(enterprise, "enterprise-admin")), Instant.now());
        }
    }

    @Test
    void forciblyTerminatedProcessLeavesApprovalThatAFreshJavaProcessCanContinue() throws Exception {
        plugin();
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        var first = RunProcessTestFixture.start(environment, processDirectory, enterprise, run, "p04-before-approval-process");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (first.isAlive() && !state(run).equals("waiting_approval") && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("waiting_approval", state(run), "独立进程没有保存确认，详见 target/p04-before-approval-process.log");
            assertEquals(0, writes.get());
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
        } finally {
            if (first.isAlive()) {
                first.destroyForcibly();
                first.waitFor(10, TimeUnit.SECONDS);
            }
        }
        decision(approvals(run).get(0), "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        var second = RunProcessTestFixture.start(environment, processDirectory, enterprise, run, "p04-after-approval-process");
        try {
            assertTrue(second.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, second.exitValue(), "独立进程不能继续确认，详见 target/p04-after-approval-process.log");
            assertEquals("completed", state(run));
            assertEquals(1, writes.get());
            assertEquals(callsBefore + 2, modelCalls.get());
            assertEquals(1, count("agent_run"));
            assertEquals(1, count("tool_call"));
            assertEquals("原始固定正文", received.get().path("text").asText());
        } finally {
            if (second.isAlive()) {
                second.destroyForcibly();
                second.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void stoppingOrExpiringPendingApprovalPreventsLateDecision() throws Exception {
        plugin();
        var first = waiting();
        String stopped = first.path("runId").asText();
        var firstApproval = approvals(stopped).get(0);
        lifecycle.stopForUser(enterprise, admin);
        assertEquals("cancelled", state(stopped));
        assertEquals("revoked", approvals(stopped).get(0).path("status").asText());
        decision(firstApproval, "approve", UUID.randomUUID().toString()).andExpect(status().isConflict());
        resetModel();
        var second = waiting();
        String expired = second.path("runId").asText();
        var secondApproval = approvals(expired).get(0);
        databaseAccess.mapper(RunApprovalSqlMapper.class).update(new LambdaUpdateWrapper<RunApprovalRow>().eq(RunApprovalRow::getRunId, (expired)).set(RunApprovalRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
        }
        assertEquals("failed", state(expired));
        assertEquals("expired", approvals(expired).get(0).path("status").asText());
        decision(secondApproval, "approve", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(0, writes.get());
    }
}
