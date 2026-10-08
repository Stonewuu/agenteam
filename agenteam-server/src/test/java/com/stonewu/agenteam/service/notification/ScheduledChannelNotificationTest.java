package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.notification.request.ChannelDeliveryRetryRequest;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.schedule.ScheduleActionReconciler;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleOccurrenceCancellation;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 定时提交与平台发送的共同验证，真实请求使用与官方字段一致的模拟响应。 */
@Import({SharedEnterpriseTestEdition.class, ScheduledChannelNotificationTest.PlatformConfiguration.class})
class ScheduledChannelNotificationTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    @Autowired private ScheduleManagementService plans;
    @Autowired private ScheduleTriggerService triggers;
    @Autowired private ScheduleQueryService scheduleQueries;
    @Autowired private ScheduleActionWorker actionWorker;
    @Autowired private ScheduleActionReconciler reconciler;
    @Autowired private ScheduleOccurrenceCancellation cancellations;
    @Autowired private ScheduleOccurrenceSqlMapper occurrences;
    @Autowired private BackgroundJobSqlMapper jobs;
    @Autowired private NotificationSqlMapper notices;
    @Autowired private NotificationChannelDeliveryMapper deliveries;
    @Autowired private ChannelDeliveryWorker worker;
    @Autowired private ChannelDeliveryTransactions transactions;
    @Autowired private ChannelDeliveryManagement management;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private UserChannelBindingMapper bindings;
    @Autowired private IntegrationQueryService integrations;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private TransactionTemplate tx;

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean
        @Primary
        IntegrationEndpoints endpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @BeforeEach
    void resetPlatform() {
        PLATFORM.reset();
    }

    @AfterEach
    void stopPendingWork() {
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .in(BackgroundJobRow::getKind, "scheduled_action", "channel_delivery").in(BackgroundJobRow::getStatus, "queued", "leased")
            .set(BackgroundJobRow::getStatus, "cancelled").set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wecom", "feishu"})
    void scheduledNotificationsWaitForActualPlatformAcceptance(String provider) throws Exception {
        var occurrence = enqueue(configure(provider));
        assertEquals("running", row(occurrence.id()).getStatus());
        assertEquals(1, noticeCount(occurrence.id()));
        var send = delivery(occurrence.id());
        assertEquals("scheduled", send.getDeliveryReason());
        PLATFORM.reply(provider + ".token");
        PLATFORM.reply(provider + ".accepted");
        assertTrue(worker.runOnce());
        PLATFORM.take();
        String request = PLATFORM.take().body();
        assertTrue(request.contains(provider.equals("wecom") ? "member_001" : "ou_test_member"));
        reconciler.reconcile(row(occurrence.id()));
        var completed = scheduleQueries.get(actor(), occurrence.scheduleId());
        assertEquals("completed", completed.latestOccurrence().status());
        assertEquals(2, completed.latestOccurrence().actionResult().get("totalCount"));
        assertEquals(1, completed.latestOccurrence().actionResult().get("acceptedCount"));
        assertNull(completed.activeOccurrenceId());
        assertEquals(0, count("agent_run"));
        assertEquals(0, reserved());
    }

    @Test
    void rebindingAfterTheSnapshotDoesNotRedirectTheOldMessageToTheNewIdentity() {
        var app = configure("feishu");
        var plan = plans.create(actor(), request(app));
        var occurrence = triggers.manual(actor(), plan.id(), UUID.randomUUID().toString());
        var original = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getConnectionId, app.getId())
            .eq(UserChannelBindingRow::getUserId, admin));
        bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, original.getId())
            .set(UserChannelBindingRow::getStatus, "revoked").set(UserChannelBindingRow::getRevokedAt, Instant.now())
            .set(UserChannelBindingRow::getRevision, original.getRevision() + 1));
        var replacement = new UserChannelBindingRow();
        replacement.setId(UUID.randomUUID().toString());
        replacement.setEnterpriseId(enterprise);
        replacement.setConnectionId(app.getId());
        replacement.setUserId(admin);
        replacement.setExternalSubjectType("feishu_open_id");
        replacement.setExternalSubjectId("ou_new_identity");
        replacement.setAuthorizedAt(Instant.now());
        bindings.insert(replacement);
        assertTrue(actionWorker.runOnce());
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("partially_failed", row(occurrence.id()).getStatus());
        var send = delivery(occurrence.id());
        assertEquals(original.getId(), send.getBindingId());
        assertEquals("cancelled", send.getStatus());
        assertEquals(0, send.getAttemptCount());
        assertFalse(worker.runOnce());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    @Test
    void retryingOnlyTheFailedPlatformDoesNotRepeatTheSuccessfulPlatformOrInAppNotice() throws Exception {
        var wecom = configure("wecom");
        var feishu = configure("feishu");
        var input = request(wecom);
        var plan = plans.create(actor(), new ScheduleWriteRequest(input.name(), null, null, null, input.frequency(), null, input.localTime(), List.of(), null,
            input.timezone(), true, 0, new ScheduleActionRequest("notification.send", 1, Map.of("title", "双渠道通知", "body", "只重试失败渠道",
                "recipients", List.of(Map.of("userId", admin, "connectionIds", List.of(wecom.getId(), feishu.getId())))))));
        var occurrence = triggers.manual(actor(), plan.id(), UUID.randomUUID().toString());
        assertTrue(actionWorker.runOnce());
        var selected = deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)
            .orderByAsc(NotificationChannelDeliveryRow::getCreatedAt, NotificationChannelDeliveryRow::getId));
        for (var send : selected) {
            boolean isWecom = wecom.getId().equals(send.getConnectionId());
            PLATFORM.reply(isWecom ? "wecom.token" : "feishu.token");
            PLATFORM.reply(isWecom ? "wecom.invalidRecipient" : "feishu.accepted");
            assertTrue(worker.runOnce());
            PLATFORM.take();
            PLATFORM.take();
        }
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("partially_failed", row(occurrence.id()).getStatus());
        var failed = deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)
            .eq(NotificationChannelDeliveryRow::getConnectionId, wecom.getId()));
        management.retry(actor().user(), enterprise, false, failed.getId(), failed.getRevision(), new ChannelDeliveryRetryRequest(false));
        PLATFORM.reply("wecom.accepted");
        assertTrue(worker.runOnce());
        PLATFORM.take();
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("completed", row(occurrence.id()).getStatus());
        assertEquals(1, noticeCount(occurrence.id()));
        var successful = deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)
            .eq(NotificationChannelDeliveryRow::getConnectionId, feishu.getId()));
        assertEquals(1, successful.getAttemptCount());
        assertEquals("accepted", successful.getStatus());
        assertEquals(0, count("agent_run"));
    }

    @Test
    void stoppingWaitingChannelsKeepsTheDeliveredInAppNotification() {
        var occurrence = enqueue(configure("feishu"));
        var cancelled = cancellations.cancel(actor(), occurrence.scheduleId(), occurrence.id());
        assertEquals("partially_failed", cancelled.status());
        assertEquals(1, cancelled.actionResult().get("inAppCount"));
        assertEquals(1, cancelled.actionResult().get("cancelledCount"));
        assertEquals("cancelled", delivery(occurrence.id()).getStatus());
        assertFalse(worker.runOnce());
        assertEquals(1, noticeCount(occurrence.id()));
    }

    @Test
    void stoppingDuringARequestKeepsThePlanActiveUntilItsRealResultIsSaved() {
        var occurrence = enqueue(configure("feishu"));
        var lease = transactions.claim("started-request").orElseThrow();
        assertNotNull(transactions.prepare(lease));
        var attempt = transactions.begin(lease);
        assertNotNull(attempt);
        var stopping = cancellations.cancel(actor(), occurrence.scheduleId(), occurrence.id());
        assertEquals("running", stopping.status());
        assertEquals(occurrence.id(), scheduleQueries.get(actor(), occurrence.scheduleId()).activeOccurrenceId());
        assertEquals("SCHEDULE_BUSY", assertThrows(ApiException.class, () -> triggers.manual(actor(), occurrence.scheduleId(), UUID.randomUUID().toString())).code());
        transactions.complete(lease, attempt.attemptId(), result(ChannelSendResult.Outcome.ACCEPTED));
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("completed", row(occurrence.id()).getStatus());
        assertNull(scheduleQueries.get(actor(), occurrence.scheduleId()).activeOccurrenceId());
    }

    @Test
    void manualRetryReoccupiesOnlyTheOriginalOccurrenceAndDoesNotReplaySuccessfulTargets() {
        var occurrence = enqueue(configure("feishu"));
        var lease = transactions.claim("failed-request").orElseThrow();
        transactions.prepare(lease);
        var attempt = transactions.begin(lease);
        transactions.complete(lease, attempt.attemptId(), result(ChannelSendResult.Outcome.PERMANENT_FAILURE));
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("partially_failed", row(occurrence.id()).getStatus());
        var failed = delivery(occurrence.id());
        var second = triggers.manual(actor(), occurrence.scheduleId(), UUID.randomUUID().toString());
        var busy = assertThrows(ApiException.class, () -> management.retry(actor().user(), enterprise, false, failed.getId(), failed.getRevision(), new ChannelDeliveryRetryRequest(false)));
        assertEquals("SCHEDULE_BUSY", busy.code());
        assertEquals("partially_failed", row(occurrence.id()).getStatus());
        cancellations.cancel(actor(), second.scheduleId(), second.id());
        var retry = management.retry(actor().user(), enterprise, false, failed.getId(), failed.getRevision(), new ChannelDeliveryRetryRequest(false));
        assertEquals("pending", retry.delivery().status());
        assertEquals(occurrence.id(), scheduleQueries.get(actor(), occurrence.scheduleId()).activeOccurrenceId());
        assertEquals(1, noticeCount(occurrence.id()));
        var next = transactions.claim("retry-request").orElseThrow();
        transactions.prepare(next);
        var nextAttempt = transactions.begin(next);
        transactions.complete(next, nextAttempt.attemptId(), result(ChannelSendResult.Outcome.ACCEPTED));
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("completed", row(occurrence.id()).getStatus());
        assertEquals(1, noticeCount(occurrence.id()));
        assertEquals(2, delivery(occurrence.id()).getAttemptCount());
        assertNull(scheduleQueries.get(actor(), occurrence.scheduleId()).activeOccurrenceId());
    }

    @ParameterizedTest
    @EnumSource(value = ChannelSendResult.Outcome.class, names = {"RETRYABLE_FAILURE", "UNKNOWN"})
    void stoppingDuringARequestPreventsAutomaticRetryAfterTemporaryOrUnknownResults(ChannelSendResult.Outcome outcome) {
        var occurrence = enqueue(configure("feishu"));
        var lease = transactions.claim("stopping-request").orElseThrow();
        transactions.prepare(lease);
        var attempt = transactions.begin(lease);
        cancellations.cancel(actor(), occurrence.scheduleId(), occurrence.id());
        transactions.complete(lease, attempt.attemptId(), result(outcome));
        reconciler.reconcile(row(occurrence.id()));
        assertEquals(outcome == ChannelSendResult.Outcome.UNKNOWN ? "unknown" : "cancelled", delivery(occurrence.id()).getStatus());
        assertEquals(outcome == ChannelSendResult.Outcome.UNKNOWN ? "unknown" : "partially_failed", row(occurrence.id()).getStatus());
        assertNull(scheduleQueries.get(actor(), occurrence.scheduleId()).activeOccurrenceId());
        assertFalse(worker.runOnce());
        assertEquals(1, delivery(occurrence.id()).getAttemptCount());
    }

    @Test
    void aStoppedRequestRecoveredAfterWorkerExitRemainsUnknownAndIsNotSentAgain() {
        var occurrence = enqueue(configure("feishu"));
        var lease = transactions.claim("exiting-request").orElseThrow();
        transactions.prepare(lease);
        transactions.begin(lease);
        cancellations.cancel(actor(), occurrence.scheduleId(), occurrence.id());
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, lease.id())
            .set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        var replacement = transactions.claim("replacement-request").orElseThrow();
        assertNull(transactions.prepare(replacement));
        reconciler.reconcile(row(occurrence.id()));
        assertEquals("unknown", delivery(occurrence.id()).getStatus());
        assertEquals("unknown", row(occurrence.id()).getStatus());
        assertEquals(1, delivery(occurrence.id()).getAttemptCount());
        assertFalse(worker.runOnce());
    }

    @Test
    void unknownPlatformResultsAreNotReportedAsKnownFailureOrSuccess() {
        var occurrence = enqueue(configure("feishu"));
        var lease = transactions.claim("unknown-request").orElseThrow();
        transactions.prepare(lease);
        var attempt = transactions.begin(lease);
        var send = delivery(occurrence.id());
        deliveries.update(new LambdaUpdateWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getId, send.getId())
            .set(NotificationChannelDeliveryRow::getRetryDeadlineAt, Instant.now().minusSeconds(1)));
        transactions.complete(lease, attempt.attemptId(), result(ChannelSendResult.Outcome.UNKNOWN));
        reconciler.reconcile(row(occurrence.id()));
        var unknown = scheduleQueries.get(actor(), occurrence.scheduleId()).latestOccurrence();
        assertEquals("unknown", unknown.status());
        assertEquals(1, unknown.actionResult().get("unknownCount"));
        assertEquals(1, unknown.actionResult().get("inAppCount"));
    }

    private ScheduleOccurrenceView enqueue(EnterpriseIntegrationRow app) {
        var plan = plans.create(actor(), request(app));
        var occurrence = triggers.manual(actor(), plan.id(), UUID.randomUUID().toString());
        assertTrue(actionWorker.runOnce());
        return occurrence;
    }

    private ScheduleWriteRequest request(EnterpriseIntegrationRow app) {
        return new ScheduleWriteRequest("每日通知", null, null, null, "daily", null, "09:15", List.of(), null, "Asia/Shanghai", true, 0,
            new ScheduleActionRequest("notification.send", 1, Map.of("title", "参加例会", "body", "请提前准备。", "recipients",
                List.of(Map.of("userId", admin, "connectionIds", List.of(app.getId()))))));
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private ScheduledOccurrenceRow row(String id) {
        return occurrences.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterprise).eq(ScheduledOccurrenceRow::getId, id));
    }

    private NotificationChannelDeliveryRow delivery(String occurrence) {
        var notice = notices.selectOne(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise).eq(NotificationRow::getSourceOccurrenceId, occurrence));
        return deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getNotificationId, notice.getId()));
    }

    private long noticeCount(String occurrence) {
        return notices.selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise).eq(NotificationRow::getSourceOccurrenceId, occurrence));
    }

    private EnterpriseIntegrationRow configure(String provider) {
        return new ChannelDeliveryFixtures(tx, secrets, integrations, connections, bindings).configure(enterprise, admin, provider);
    }

    private ChannelSendResult result(ChannelSendResult.Outcome outcome) {
        return new ChannelSendResult(outcome, "0", outcome == ChannelSendResult.Outcome.ACCEPTED ? "om_test_message" : null,
            outcome == ChannelSendResult.Outcome.ACCEPTED ? null : "TEST_PLATFORM_RESULT", "模拟发送结果", null, 200, null);
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动模拟渠道服务", failure);
        }
    }
}
