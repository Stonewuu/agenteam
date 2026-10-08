package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ScheduleExecutionTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.execution.RunApprovalSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.notification.NotificationFixtureMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发与失败通过真实数据库验证，实际模型和工作流仍由原执行器处理。
 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduleTriggerApiTest extends ScheduleExecutionTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();



    @Autowired
    private NotificationDeliveryService notifications;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void concurrentSchedulersCreateOnlyOneRunAndStoppingAllowsANewIndependentManualOccurrence() throws Exception {
        Instant due = recentTime();
        String id = create(payload(due));
        makeDue(id, due, due.minusSeconds(10));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                process(id);
                return null;
            });
            var second = pool.submit(() -> {
                start.await();
                process(id);
                return null;
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, count("scheduled_occurrence"));
        assertEquals(1, count("agent_run"));
        assertEquals(1, reserved());
        var occurrence = latest(id);
        schemas.validate("ScheduleOccurrence", occurrence);
        String run = occurrence.path("runId").asText();
        assertEquals("scheduled", occurrence.path("triggerKind").asText());
        assertEquals("queued", occurrence.path("status").asText());
        assertEquals(1, occurrence.path("attemptCount").asInt());
        assertEquals("scheduled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getMode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getMode()).toList()));
        assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getMaxAttempts).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getMaxAttempts()).toList()));
        String next = detail(id).path("nextRunAt").asText();
        var busy = write(path(id) + "/run", null, UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("SCHEDULE_BUSY", json.readTree(busy.getResponse().getContentAsString()).at("/error/code").asText());
        stop(run);
        assertEquals("cancelled", latest(id).path("status").asText());
        assertTrue(detail(id).path("activeOccurrenceId").isNull());
        assertEquals(0, reserved());
        String key = "Manual-Test-Key-123456";
        var manual = manual(id, key);
        assertEquals("manual", manual.path("triggerKind").asText());
        assertEquals(manual, manual(id, key));
        assertNotEquals(occurrence.path("conversationId"), manual.path("conversationId"));
        assertEquals(next, detail(id).path("nextRunAt").asText());
        assertEquals(2, count("scheduled_occurrence"));
        assertEquals(2, count("agent_conversation"));
        String config = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getId, (manual.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList());
        assertTrue(json.readTree(config).path("previousRunId").isNull());
        assertEquals(version, json.readTree(config).path("agentVersionId").asText());
        stop(manual.path("runId").asText());
        var caseChanged = manual(id, key.toLowerCase());
        assertNotEquals(manual.path("id"), caseChanged.path("id"));
    }


    @Test
    void outageCatchupRunsOnlyTheLastRecentTimeAndDoesNotReplayOlderDays() throws Exception {
        Instant due = recentTime();
        String id = create(payload(due));
        makeDue(id, due.minus(2, ChronoUnit.DAYS), due.minus(3, ChronoUnit.DAYS));
        process(id);
        process(id);
        assertEquals(3, count("scheduled_occurrence"));
        assertEquals(1, count("agent_run"));
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getScheduleId, (id)).eq(ScheduledOccurrenceRow::getStatus, "missed"))));
        assertEquals(Timestamp.from(due), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledOccurrenceRow>().select(ScheduledOccurrenceRow::getScheduledFor).eq(ScheduledOccurrenceRow::getScheduleId, (id)).eq(ScheduledOccurrenceRow::getStatus, "queued")).stream().map(fixtureRecord -> (fixtureRecord.getScheduledFor() == null ? null : Timestamp.from(fixtureRecord.getScheduledFor()))).toList()));
        assertTrue(Instant.parse(detail(id).path("nextRunAt").asText()).isAfter(Instant.now()));
    }

    @Test
    void expiredOneTimeAndLongUnattendedPlansDoNotRunButFutureMonthlyPlansRemainEnabled() throws Exception {
        Instant due = recentTime().minusSeconds(1200);
        var once = payload(due);
        once.put("frequency", "once");
        once.put("localDate", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString());
        String expired = create(once);
        databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (expired)).set(ScheduledTaskRow::getLocalDate, (due.atZone(ZoneOffset.UTC).toLocalDate())));
        makeDue(expired, due, due.minusSeconds(1));
        process(expired);
        assertEquals("missed", latest(expired).path("status").asText());
        assertFalse(detail(expired).path("enabled").asBoolean());
        String unattended = create(payload(recentTime()));
        makeDue(unattended, recentTime(), Instant.now().minus(31, ChronoUnit.DAYS));
        process(unattended);
        assertFalse(detail(unattended).path("enabled").asBoolean());
        assertTrue(detail(unattended).path("pauseReason").asText().contains("三十天"));
        var monthly = payload(recentTime());
        monthly.put("frequency", "monthly");
        monthly.put("monthDay", 31);
        String future = create(monthly);
        String next = detail(future).path("nextRunAt").asText();
        databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (future)).set(ScheduledTaskRow::getLastCheckedAt, (Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)))));
        assertTrue(triggers.candidates().stream().anyMatch(item -> item.id().equals(future)));
        process(future);
        assertEquals(next, detail(future).path("nextRunAt").asText());
        assertTrue(detail(future).path("enabled").asBoolean());
        assertEquals(0, count("agent_run"));
        assertFalse(triggers.candidates().stream().anyMatch(item -> item.id().equals(future)));
    }

    @Test
    void pausingTheHireImmediatelyPausesItsPlansAndDeliversEachOwnerNoticeOnce() throws Exception {
        String first = create(payload(recentTime()));
        String second = create(payload(recentTime()));
        var hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow();
        change(HttpMethod.PATCH, base() + "/hires/" + hire.id(), Map.of("status", "paused"), Long.toString(hire.revision()), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertFalse(detail(first).path("enabled").asBoolean());
        assertFalse(detail(second).path("enabled").asBoolean());
        assertTrue(detail(first).path("pauseReason").asText().contains("雇佣"));
        assertEquals(0, count("notification"));
        var pending = notifications.candidates().stream().filter(item -> item.enterprise().equals(enterprise)).toList();
        assertEquals(2, pending.size());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> notifications.deliver(pending.getFirst()));
            var two = pool.submit(() -> notifications.deliver(pending.getLast()));
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        for (var item : pending) {
            notifications.deliver(item);
        }
        assertEquals(2, count("notification"));
        assertEquals(List.of(1L, 2L), databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getSequenceNo).orderByAsc(NotificationRow::getSequenceNo).eq(NotificationRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getSequenceNo()).toList());
        assertEquals(List.of(admin), databaseAccess.mapper(NotificationFixtureMapper.class).scheduleTriggerApiPausingTheHireImmediatelyPausesItsPlansAndDeliversEachOwnerNoticeOnceList7(enterprise));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(IdentityQueryMapper.class).selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getNotificationSequence).eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (admin))).stream().map(fixtureRecord -> (fixtureRecord.getNotificationSequence() == null ? null : Math.toIntExact(fixtureRecord.getNotificationSequence()))).toList()));
    }

    @Test
    void realExecutionResultsAndEachNewOccurrenceKeepTheirOwnConversation() throws Exception {
        String id = create(payload(recentTime()));
        var requests = new CopyOnWriteArrayList<JsonNode>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var frame = Map.of("id", UUID.randomUUID().toString(), "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "本次独立计划已完成"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(frame) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        var first = manual(id, UUID.randomUUID().toString());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(first.path("runId").asText()).equals("completed"));
        }
        assertEquals("completed", latest(id).path("status").asText());
        assertTrue(detail(id).path("activeOccurrenceId").isNull());
        var second = manual(id, UUID.randomUUID().toString());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(second.path("runId").asText()).equals("completed"));
        }
        assertNotEquals(first.path("conversationId"), second.path("conversationId"));
        assertEquals(2, requests.size());
        for (var request : requests) {
            assertEquals(1, request.path("messages").findValuesAsText("role").stream().filter("user"::equals).count());
        }
        for (var item : List.of(first, second)) {
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + item.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertEquals(2, snapshot.path("messages").size());
            assertTrue(snapshot.toString().contains("本次独立计划已完成"));
            long sequence = 0;
            for (var event : events.after(enterprise, item.path("conversationId").asText(), 0, 1000)) {
                assertEquals(Long.toString(++sequence), event.sequence());
                schemas.validate("ExecutionEvent", json.valueToTree(event));
            }
        }
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)));
        assertEquals(0, reserved());
        // 完成通知给前台对话留出确认时间，测试将本企业待发送通知推进到可发送时间。
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "notification")
            .eq(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getAvailableAt, Instant.now().minusSeconds(1)));
        for (var pending : notifications.candidates()) {
            if (pending.enterprise().equals(enterprise)) {
                notifications.deliver(pending);
            }
        }
        assertEquals(2, count("notification"));
    }

    @Test
    void waitingForApprovalBlocksTheNextOccurrenceAndExpirationReleasesThePlan() throws Exception {
        useApprovalWorkflow();
        String id = create(payload(recentTime()));
        var accepted = manual(id, UUID.randomUUID().toString());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(run).equals("waiting_approval"));
        }
        assertEquals("waiting_approval", latest(id).path("status").asText());
        Instant due = recentTime();
        makeDue(id, due, due.minusSeconds(1));
        process(id);
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getScheduleId, (id)).eq(ScheduledOccurrenceRow::getStatus, "skipped").eq(ScheduledOccurrenceRow::getReasonCode, "SCHEDULE_PREVIOUS_ACTIVE"))));
        assertEquals(1, count("agent_run"));
        assertEquals(1, count("run_approval"));
        assertEquals(accepted.path("id"), detail(id).path("activeOccurrenceId"));
        assertEquals(run, detail(id).at("/activeOccurrence/runId").asText());
        assertEquals("waiting_approval", detail(id).at("/activeOccurrence/status").asText());
        write(path(id) + "/run", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        databaseAccess.mapper(RunApprovalSqlMapper.class).update(new LambdaUpdateWrapper<RunApprovalRow>().eq(RunApprovalRow::getRunId, (run)).set(RunApprovalRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        for (var candidate : lifecycle.expiredApprovals()) {
            if (candidate.runId().equals(run)) {
                lifecycle.expireApproval(candidate);
            }
        }
        assertEquals("failed", runStatus(run));
        assertEquals("failed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledOccurrenceRow>().select(ScheduledOccurrenceRow::getStatus).eq(ScheduledOccurrenceRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertTrue(detail(id).path("activeOccurrenceId").isNull());
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getCurrentAttemptNo).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getCurrentAttemptNo()).toList()));
        for (var pending : notifications.candidates()) {
            if (pending.enterprise().equals(enterprise)) {
                notifications.deliver(pending);
            }
        }
        assertEquals(2, count("notification"));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, (enterprise)).eq(NotificationRow::getCategory, "approval"))));
        assertTrue(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getTitle).eq(NotificationRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getTitle()).toList().stream().allMatch(title -> title.contains("每日整理")));
        assertEquals(List.of(accepted.path("conversationId").asText()), databaseAccess.mapper(NotificationFixtureMapper.class).scheduleTriggerApiWaitingForApprovalBlocksTheNextOccurrenceAndExpirationReleasesThePlanList10(enterprise));
    }

    @Test
    void approvalDecisionQueuesTheSameOccurrenceAndKeepsAUserPausedPlanPaused() throws Exception {
        useApprovalWorkflow();
        String id = create(payload(recentTime()));
        var accepted = manual(id, UUID.randomUUID().toString());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(run).equals("waiting_approval"));
        }
        change(HttpMethod.PATCH, path(id) + "/enabled", Map.of("enabled", false), detail(id).path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("waiting_approval", runStatus(run));
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("queued", latest(id).path("status").asText());
        assertEquals(accepted.path("id"), detail(id).path("activeOccurrenceId"));
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(run).equals("completed"));
        }
        assertEquals("completed", latest(id).path("status").asText());
        assertFalse(detail(id).path("enabled").asBoolean());
        assertTrue(detail(id).path("nextRunAt").isNull());
        assertEquals(1, count("run_attempt"));
        assertEquals(1, count("scheduled_occurrence"));
        assertEquals(0, reserved());
    }

    private void useApprovalWorkflow() throws Exception {
        var graph = Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(node("start", "start", Map.of("inputSchema", Map.of("type", "object"))), node("approval", "approval", Map.of("title", "确认本次处理", "description", "核对本次要求", "inputMapping", Map.of("text", "${input.text}"))), node("yes", "transform", Map.of("fields", List.of(Map.of("target", "text", "literal", "已同意")))), node("no", "transform", Map.of("fields", List.of(Map.of("target", "text", "literal", "已拒绝")))), node("end", "end", Map.of("outputMapping", Map.of("text", "本次决定已处理")))), "edges", List.of(edge("start", "approval", "default"), edge("approval", "yes", "approve"), edge("approval", "no", "reject"), edge("yes", "end", "default"), edge("no", "end", "default")));
        String workflow = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "计划确认流程", "description", "验证真实确认", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String flowVersion = data(change(HttpMethod.POST, base() + "/resources/" + workflow + "/publish", Map.of("releaseNote", "计划确认"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(config -> {
            config.put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", flowVersion).put("maxSteps", 50);
            config.remove("temperature");
            config.set("workflowVersionIds", json.valueToTree(List.of(flowVersion)));
        });
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config);
    }

    private Map<String, String> edge(String from, String to, String branch) {
        return Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", branch);
    }







    private void stop(String run) throws Exception {
        write(base() + "/runs/" + run + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }

    private String runStatus(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }



}
