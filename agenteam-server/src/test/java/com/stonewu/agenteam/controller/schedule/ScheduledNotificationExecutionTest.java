package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.service.schedule.ScheduleActionReconciler;
import com.stonewu.agenteam.service.schedule.ScheduleActionStore;
import com.stonewu.agenteam.service.schedule.ScheduleActionTransactions;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleOccurrenceCancellation;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用真实数据库验证固定参数、事务回滚、失效接收人和两次租约之间的写入隔离。 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduledNotificationExecutionTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private ScheduleManagementService plans;
    @Autowired private ScheduleTriggerService triggers;
    @Autowired private ScheduleQueryService queries;
    @Autowired private ScheduleActionWorker worker;
    @Autowired private ScheduleActionTransactions actions;
    @Autowired private ScheduleActionReconciler reconciler;
    @Autowired private ScheduleOccurrenceCancellation cancellations;
    @Autowired private ScheduleOccurrenceSqlMapper occurrences;
    @Autowired private BackgroundJobSqlMapper jobs;
    @Autowired private NotificationSqlMapper notices;
    @Autowired private IdentityQueryMapper members;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterEach
    void stopQueuedActions() {
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "scheduled_action").in(BackgroundJobRow::getStatus, "queued", "leased")
            .set(BackgroundJobRow::getStatus, "cancelled").set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void preparationUsesTheOriginalSnapshotAndDoesNotReserveAgentQuota() throws Exception {
        var actor = actor(admin);
        var plan = plans.create(actor, request("原通知内容", List.of(admin)));
        String key = UUID.randomUUID().toString();
        var queued = triggers.manual(actor, plan.id(), key);
        assertEquals("queued", queued.status());
        assertNull(queued.runId());
        assertEquals("captured", queued.snapshotOrigin());
        assertEquals(0, count("notification"));
        assertEquals(0, count("agent_run"));
        assertEquals(0, reserved());
        var current = queries.get(actor, plan.id());
        plans.update(actor, plan.id(), request("后来编辑的内容", List.of(admin)), Long.parseLong(current.revision()));
        assertTrue(worker.runOnce());
        reconciler.reconcile(row(queued.id()));
        var completed = queries.get(actor, plan.id()).latestOccurrence();
        assertEquals("completed", completed.status());
        assertEquals(1, completed.actionResult().get("inAppCount"));
        var detail = queries.occurrence(actor, plan.id(), queued.id());
        schemas.validate("ScheduleOccurrenceDetail", json.valueToTree(detail));
        assertEquals("原通知内容", detail.occurrence().actionSnapshot().get("body"));
        assertEquals("delivered", detail.recipients().getFirst().inAppStatus());
        assertNull(queries.get(actor, plan.id()).activeOccurrenceId());
        var saved = notices.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getSourceOccurrenceId, queued.id()));
        assertEquals(1, saved.size());
        assertEquals("原通知内容", saved.getFirst().getBody());
        assertEquals(queued.id(), triggers.manual(actor, plan.id(), key).id());
        assertFalse(worker.runOnce());
        assertEquals(1, count("notification"));
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("quota_entry"));
        schemas.validate("ScheduleOccurrence", json.valueToTree(completed));
    }

    @Test
    void aDisabledRecipientDoesNotPreventDeliveryToRemainingMembers() {
        var other = EnterpriseTestData.member(users, permissions, enterprise, "notice_" + UUID.randomUUID().toString().substring(0, 8),
            "notice-test-password-2026", "通知接收成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var actor = actor(admin);
        var plan = plans.create(actor, request("逐人发送", List.of(admin, other.id())));
        var queued = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        members.update(new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
            .eq(EnterpriseMemberRow::getUserId, other.id()).set(EnterpriseMemberRow::getStatus, "disabled"));
        assertTrue(worker.runOnce());
        reconciler.reconcile(row(queued.id()));
        var outcome = queries.get(actor, plan.id()).latestOccurrence();
        assertEquals("partially_failed", outcome.status());
        assertEquals(2, outcome.actionResult().get("totalCount"));
        assertEquals(1, outcome.actionResult().get("inAppCount"));
        assertEquals(1, outcome.actionResult().get("blockedCount"));
        var detail = queries.occurrence(actor, plan.id(), queued.id());
        assertTrue(detail.recipients().stream().anyMatch(value -> value.userId().equals(other.id()) && "blocked".equals(value.inAppStatus())));
        assertTrue(queries.get(actor, plan.id()).enabled());
        assertEquals(List.of(admin), notices.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getSourceOccurrenceId, queued.id()))
            .stream().map(NotificationRow::getUserId).toList());
    }

    @Test
    void aMemberWithoutAgentPermissionCanExecuteSelfNotificationsAndRevocationBlocksQueuedWork() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "notice_planner", "通知计划人", "仅管理本人的通知计划", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("schedule.view", "schedule.manage"));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "planner_" + UUID.randomUUID().toString().substring(0, 8),
            "notice-test-password-2026", "通知计划人", List.of(role));
        var actor = actor(member.id());
        assertFalse(actor.permissions().contains("agent.run"));
        var plan = plans.create(actor, request("只发送通知", List.of(member.id())));
        var first = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        assertTrue(worker.runOnce());
        reconciler.reconcile(row(first.id()));
        assertEquals("completed", row(first.id()).getStatus());
        var second = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        permissions.replaceRolePermissions(enterprise, role, Set.of("schedule.view"));
        assertTrue(worker.runOnce());
        assertEquals("blocked", row(second.id()).getStatus());
        assertEquals("SCHEDULE_PERMISSION_DENIED", row(second.id()).getReasonCode());
        assertFalse(queries.get(actor(member.id()), plan.id()).enabled());
        assertEquals(1, notices.selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)));
    }

    @Test
    void anExpiredLeaseRollsBackAllBusinessWritesBeforeTheNewOwnerSubmits() {
        var actor = actor(admin);
        var plan = plans.create(actor, request("只应写入一次", List.of(admin)));
        var queued = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        var first = actions.claim("first-worker").orElseThrow();
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, first.id())
            .set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        var second = actions.claim("second-worker").orElseThrow();
        assertEquals(first.id(), second.id());
        assertTrue(second.leaseVersion() > first.leaseVersion());
        assertThrows(ScheduleActionStore.LeaseLostException.class, () -> actions.submit(first));
        assertEquals(0, count("notification"));
        assertEquals("queued", row(queued.id()).getStatus());
        actions.submit(second);
        reconciler.reconcile(row(queued.id()));
        assertEquals("completed", row(queued.id()).getStatus());
        assertEquals(1, count("notification"));
        assertThrows(ScheduleActionStore.LeaseLostException.class, () -> actions.submit(second));
        assertEquals(1, count("notification"));
    }

    @Test
    void stoppingBeforePreparationReleasesThePlanAndDoesNotSendAnything() throws Exception {
        var actor = actor(admin);
        var plan = plans.create(actor, request("不应发送", List.of(admin)));
        var queued = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        String path = base() + "/schedules/" + plan.id() + "/occurrences/" + queued.id() + "/cancel";
        String key = UUID.randomUUID().toString();
        var cancelled = data(write(path, null, key).andExpect(status().isOk()).andReturn());
        assertEquals("cancelled", cancelled.path("status").asText());
        assertEquals(cancelled, data(write(path, null, key).andExpect(status().isOk()).andReturn()));
        assertNull(queries.get(actor, plan.id()).activeOccurrenceId());
        assertFalse(worker.runOnce());
        assertEquals(0, count("notification"));
        assertEquals("queued", triggers.manual(actor, plan.id(), UUID.randomUUID().toString()).status());
    }

    @Test
    void stoppingAfterInAppDeliveryKeepsItsActualCompletedResult() {
        var actor = actor(admin);
        var plan = plans.create(actor, request("已经保存的通知", List.of(admin)));
        var queued = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        assertTrue(worker.runOnce());
        var cancelled = cancellations.cancel(actor, plan.id(), queued.id());
        assertEquals("completed", cancelled.status());
        assertEquals(true, cancelled.actionResult().get("cancelRequested"));
        assertEquals(1, count("notification"));
        assertNull(queries.get(actor, plan.id()).activeOccurrenceId());
    }

    @Test
    void expiredPreparedTimeBlocksTheWholeNotificationWithoutCreatingMessages() {
        var actor = actor(admin);
        var plan = plans.create(actor, request("已过时通知", List.of(admin)));
        ScheduleOccurrenceView queued = triggers.manual(actor, plan.id(), UUID.randomUUID().toString());
        occurrences.update(new LambdaUpdateWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getId, queued.id())
            .set(ScheduledOccurrenceRow::getScheduledFor, Instant.now().minusSeconds(7200)));
        assertTrue(worker.runOnce());
        assertEquals("blocked", row(queued.id()).getStatus());
        assertEquals("SCHEDULE_NOTIFICATION_EXPIRED", row(queued.id()).getReasonCode());
        assertNull(queries.get(actor, plan.id()).activeOccurrenceId());
        assertEquals(0, count("notification"));
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private ScheduledOccurrenceRow row(String id) {
        return occurrences.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterprise)
            .eq(ScheduledOccurrenceRow::getId, id));
    }

    private ScheduleWriteRequest request(String body, List<String> recipients) {
        return new ScheduleWriteRequest("每日通知", null, null, null, "daily", null, "09:15", List.of(), null, "Asia/Shanghai", true, 0,
            new ScheduleActionRequest("notification.send", 1, Map.of("title", "参加例会", "body", body, "recipients", recipients.stream()
                .map(user -> Map.of("userId", user, "connectionIds", List.of())).toList())));
    }
}
