package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.test.background.BackgroundJobFixtureMapper;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证提交、后台执行、并发、回滚和次数边界。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationSubmissionApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p03-model-test-workspace");
        registry.add("execution.state-root", () -> "target/p03-model-test-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void concurrentDuplicateRequestCommitsOneConversationRunAndReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        var responses = concurrent(key, key);
        assertEquals(202, responses.getFirst().getResponse().getStatus());
        assertEquals(202, responses.getLast().getResponse().getStatus());
        JsonNode accepted = data(responses.getFirst());
        assertEquals(accepted, data(responses.getLast()));
        schemas.validate("RunAccepted", accepted);
        assertEquals(1, count("agent_conversation"));
        assertEquals(1, count("agent_run"));
        assertEquals(2, count("agent_message"));
        assertEquals(1, count("run_attempt"));
        assertEquals(3, count("agent_event"));
        assertEquals(1, reserved());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getStatus, "queued"))));
        assertEquals(version, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getAgentVersionId).eq(AgentRunRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getAgentVersionId()).toList()));
        var fixed = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList()));
        assertEquals("这是仅供执行器读取的内部指令。", fixed.at("/config/instructions").asText());
        assertFalse(fixed.toString().contains("isolated-execution-secret"));
        for (var event : events.after(enterprise, accepted.path("conversationId").asText(), 0, 10)) {
            schemas.validate("ExecutionEvent", json.valueToTree(event));
        }
        write(base() + "/conversations", Map.of("agentId", agent, "input", input("不同正文")), key).andExpect(status().isConflict());
    }

    @Test
    void activeConversationRejectsNewRequestAndSnapshotUsesCommittedMessages() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String id = accepted.path("conversationId").asText();
        var busy = write(base() + "/conversations/" + id + "/messages", input("另一条输入"), UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("CONVERSATION_BUSY", json.readTree(busy.getResponse().getContentAsString()).at("/error/code").asText());
        assertEquals(1, count("agent_run"));
        assertEquals(2, count("agent_message"));
        assertEquals(1, reserved());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + id).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertEquals(accepted.path("lastSequence"), snapshot.path("lastSequence"));
        assertEquals("user", snapshot.at("/messages/0/role").asText());
        assertEquals("queued", snapshot.at("/activeRun/status").asText());
        assertFalse(snapshot.toString().contains("内部指令"));
        assertFalse(snapshot.toString().contains("isolated-execution-secret"));
    }

    @Test
    void databaseFailureRollsBackMessagesQuotaEventsAndRequestResult() throws Exception {
        databaseAccess.mapper(TestDatabaseAdministrationMapper.class).rejectRunJobCreation();
        String key = UUID.randomUUID().toString();
        try {
            write(base() + "/conversations", body(), key).andExpect(status().isInternalServerError());
            assertEquals(0, count("agent_conversation"));
            assertEquals(0, count("agent_run"));
            assertEquals(0, count("agent_message"));
            assertEquals(0, count("agent_event"));
            assertEquals(0, count("quota_entry"));
            assertEquals(0, reserved());
        } finally {
            databaseAccess.mapper(TestDatabaseAdministrationMapper.class).allowRunJobCreation();
        }
        write(base() + "/conversations", body(), key).andExpect(status().isAccepted());
        assertEquals(1, count("agent_run"));
        assertEquals(1, reserved());
    }


    @Test
    void unknownFieldsUnavailableHireAndForeignConversationAreRejected() throws Exception {
        var invalid = json.valueToTree(body());
        ((ObjectNode) invalid).put("userId", admin);
        write(base() + "/conversations", invalid, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        databaseAccess.mapper(AgentHireSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, (enterprise)).set(AgentHireRow::getStatus, "paused"));
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(0, count("agent_conversation"));
        assertEquals(0, reserved());
        databaseAccess.mapper(AgentHireSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, (enterprise)).set(AgentHireRow::getStatus, "active"));
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String other = provisioning.create("不能访问其他企业对话", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void queuedCancellationReleasesQuotaAndRepeatedRetryCreatesOnlyOneNewRun() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        String cancelKey = UUID.randomUUID().toString();
        var cancelled = data(write(base() + "/runs/" + run + "/cancel", null, cancelKey).andExpect(status().isAccepted()).andReturn());
        schemas.validate("Run", cancelled);
        assertEquals("cancelled", cancelled.path("status").asText());
        assertEquals(cancelled, data(write(base() + "/runs/" + run + "/cancel", null, cancelKey).andExpect(status().isAccepted()).andReturn()));
        assertEquals(0, reserved());
        String retryKey = UUID.randomUUID().toString();
        var retried = data(write(base() + "/runs/" + run + "/retry", null, retryKey).andExpect(status().isAccepted()).andReturn());
        assertEquals(retried, data(write(base() + "/runs/" + run + "/retry", null, retryKey).andExpect(status().isAccepted()).andReturn()));
        assertEquals(2, count("agent_run"));
        assertEquals(4, count("agent_message"));
        assertEquals(1, reserved());
        var retryBlocks = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getBlocksJson).eq(AgentMessageRow::getId, (retried.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getBlocksJson()).toList()));
        assertEquals("重新执行", retryBlocks.get(0).path("label").asText());
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
    }

    @Test
    void firstStepConsumesOnceAndLateCompletionCannotReviveCancelledRun() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("test-worker").orElseThrow();
        var started = lifecycle.start(lease).orElseThrow();
        assertEquals("running", started.status());
        assertEquals(1, reserved());
        lifecycle.beforeExternalStep(lease);
        lifecycle.beforeExternalStep(lease);
        assertEquals(0, reserved());
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)));
        var cancelling = data(write(base() + "/runs/" + lease.runId() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals("cancelling", cancelling.path("status").asText());
        lifecycle.finish(lease, "completed", null, null);
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (lease.runId()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        var next = data(write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages", input("新任务"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        lifecycle.finish(lease, "completed", null, null);
        assertEquals(next.path("runId").asText(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ConversationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getActiveRunId).eq(AgentConversationRow::getId, (accepted.path("conversationId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getActiveRunId()).toList()));
    }

    @Test
    void lostLeaseBeforeStartCanBeReclaimedButRunningModelIsNotReplayed() throws Exception {
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        var first = lifecycle.claim("worker-before-crash").orElseThrow();
        databaseAccess.mapper(BackgroundJobFixtureMapper.class).expireLease(first.id());
        lifecycle.recover(first);
        var second = lifecycle.claim("worker-after-restart").orElseThrow();
        assertEquals(first.runId(), second.runId());
        assertTrue(second.version() > first.version());
        assertTrue(lifecycle.start(first).isEmpty());
        lifecycle.start(second).orElseThrow();
        lifecycle.beforeExternalStep(second);
        databaseAccess.mapper(BackgroundJobFixtureMapper.class).expireLease(second.id());
        lifecycle.recover(second);
        assertEquals("EXECUTION_INTERRUPTED", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (second.runId()))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
        assertEquals(1, count("agent_run"));
        assertEquals(0, reserved());
        lifecycle.finish(second, "completed", null, null);
        assertEquals("failed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (second.runId()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
    }


    @Test
    void committedEventBatchCanBeRetriedWithoutAppendingItsContentTwice() throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("worker-message-save").orElseThrow();
        lifecycle.start(lease).orElseThrow();
        var block = new ContentBlock("block-one", "text", null, 1, "1", "已经保存的正文", "running", null, null, null, null, null, null);
        var changes = List.of(ExecutionChange.replace(block));
        String batch = UUID.randomUUID().toString();
        messageWriter.save(lease, batch, changes);
        messageWriter.save(lease, batch, changes);
        assertEquals(5, count("agent_event"));
        assertThrows(IllegalStateException.class, () -> messageWriter.save(lease, batch, List.of(ExecutionChange.replace(block.update("不同的内容", "running")))));
        assertEquals("已经保存的正文", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContent).eq(AgentMessageRow::getId, (accepted.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContent()).toList()));
    }

    @Test
    void backgroundWorkerPersistsLongTextBeforeModelCompletionAndFinishesTheSameRun() throws Exception {
        String first = "长段".repeat(700) + "🙂".repeat(100), last = "，已经完成。";
        var finish = new CountDownLatch(1);
        modelResponse = exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var output = exchange.getResponseBody();
                output.write(("data: " + json.writeValueAsString(Map.of("id", "controlled-response", "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", first))))) + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (!finish.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("模型测试未收到继续信号");
                }
                output.write(("data: " + json.writeValueAsString(Map.of("id", "controlled-response", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", last), "finish_reason", "stop")))) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals(callsBefore, modelCalls.get());
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> first.equals(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContent).eq(AgentMessageRow::getId, (accepted.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContent()).toList())));
            assertEquals("running", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            finish.countDown();
            await(() -> "completed".equals(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList())));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            try {
                schemas.validate("ConversationSnapshot", snapshot);
            } catch (ApiException invalid) {
                throw new AssertionError("会话快照不符合正式结构：" + invalid.fieldErrors(), invalid);
            }
            assertEquals(first + last, snapshot.at("/messages/1/content").asText());
            assertTrue(snapshot.path("activeRun").isNull());
            assertEquals(callsBefore + 1, modelCalls.get());
            assertEquals(0, reserved());
            long sequence = 0;
            for (var event : events.after(enterprise, accepted.path("conversationId").asText(), 0, 100)) {
                assertEquals(Long.toString(++sequence), event.sequence());
                schemas.validate("ExecutionEvent", json.valueToTree(event));
                if (event.type().equals("message.delta")) {
                    assertTrue(event.payload().get("delta").toString().getBytes(StandardCharsets.UTF_8).length <= 4096);
                }
            }
            assertEquals(Long.toString(sequence), snapshot.path("lastSequence").asText());
            assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, (enterprise)).in(RunStepRow::getStatus, Arrays.asList("pending", "running")))));
        } finally {
            finish.countDown();
            worker.close();
        }
    }

}
