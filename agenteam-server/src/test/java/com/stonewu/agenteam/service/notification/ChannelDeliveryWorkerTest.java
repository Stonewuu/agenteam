package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelPreferenceMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelAttemptMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelAttemptRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.service.integration.ChannelRateLimiter;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.integration.IntegrationRetentionService;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经真实 MySQL、Redis 与官方协议模拟网络验证恢复、限流和发送结果。 */
@Import({SharedEnterpriseTestEdition.class, ChannelDeliveryWorkerTest.PlatformConfiguration.class})
class ChannelDeliveryWorkerTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean
        @Primary
        IntegrationEndpoints endpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @Autowired private NotificationWriteService writer;
    @Autowired private ChannelNotificationRouting routing;
    @Autowired private ChannelDeliveryPolicy policy;
    @Autowired private ChannelDeliveryWorker worker;
    @Autowired private ChannelDeliveryTransactions transactions;
    @Autowired private TransactionTemplate tx;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private IntegrationQueryService queries;
    @Autowired private ChannelRateLimiter limiter;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private UserChannelBindingMapper bindings;
    @Autowired private UserChannelPreferenceMapper preferences;
    @Autowired private NotificationChannelDeliveryMapper deliveries;
    @Autowired private NotificationChannelAttemptMapper attempts;
    @Autowired private BackgroundJobSqlMapper jobs;
    @Autowired private IntegrationRetentionService retention;
    @Autowired private NotificationSqlMapper noticeRows;
    @Autowired private NotificationDeliveryService inApp;

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
            .eq(BackgroundJobRow::getKind, "channel_delivery").in(BackgroundJobRow::getStatus, "queued", "leased")
            .set(BackgroundJobRow::getStatus, "cancelled").set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @Test
    void committedDeliveryUsesOfficialPayloadAndKeepsOneLogicalSend() throws Exception {
        var connection = configure("feishu");
        var notice = new Notice("channel:" + UUID.randomUUID(), "schedule", "开会提醒", "请查看本站通知。", null, null);
        var row = enqueue(connection, notice);
        assertEquals(row.getId(), enqueue(connection, notice).getId());
        assertEquals(1, deliveries.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.accepted");
        assertTrue(worker.runOnce());
        assertFalse(worker.runOnce());
        assertEquals("/open-apis/auth/v3/tenant_access_token/internal", PLATFORM.take().uri().getPath());
        var request = PLATFORM.take();
        assertEquals("open_id", request.query().get("receive_id_type"));
        var payload = json.readTree(request.body());
        assertEquals(row.getProviderRequestId(), payload.path("uuid").asText());
        assertTrue(payload.path("content").isTextual());
        assertTrue(json.readTree(payload.path("content").asText()).path("text").asText()
            .contains("/enterprises/" + enterprise + "/notifications/" + row.getNotificationId()));
        assertEquals("accepted", current(row).getStatus());
        assertEquals("om_test_message", current(row).getProviderMessageId());
        assertEquals("accepted", history(row).getFirst().getOutcome());
    }

    @Test
    void wecomSuccessCodeWithInvalidRecipientIsNotAccepted() throws Exception {
        var row = enqueue(configure("wecom"));
        PLATFORM.reply("wecom.token");
        PLATFORM.reply("wecom.invalidRecipient");
        worker.runOnce();
        PLATFORM.take();
        var request = json.readTree(PLATFORM.take().body());
        assertEquals("member_001", request.path("touser").asText());
        assertEquals(1, request.path("enable_duplicate_check").asInt());
        assertEquals(3600, request.path("duplicate_check_interval").asInt());
        assertEquals("failed", current(row).getStatus());
        assertNull(current(row).getAcceptedAt());
    }

    @Test
    void networkUnknownRetriesTheIdenticalMessageAndStopsAfterItsSafeWindow() throws Exception {
        var row = enqueue(configure("feishu"));
        PLATFORM.reply("feishu.token");
        PLATFORM.disconnect();
        worker.runOnce();
        PLATFORM.take();
        String original = PLATFORM.take().body();
        assertEquals("retry_wait", current(row).getStatus());
        assertEquals("unknown", history(row).getFirst().getOutcome());
        due(row);
        PLATFORM.reply("feishu.accepted");
        worker.runOnce();
        assertEquals(original, PLATFORM.take().body());
        assertEquals("accepted", current(row).getStatus());
        assertEquals(2, current(row).getAttemptCount());
        var other = enqueue(queries.require(enterprise, row.getConnectionId()));
        PLATFORM.disconnect();
        worker.runOnce();
        PLATFORM.take();
        deliveries.update(new LambdaUpdateWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getId, other.getId())
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).set(NotificationChannelDeliveryRow::getRetryDeadlineAt, Instant.now().minusSeconds(1)));
        due(other);
        worker.runOnce();
        assertEquals("unknown", current(other).getStatus());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    @Test
    void expiredLeaseRecoversUnknownAndOldWorkerCannotOverwriteNewAcceptance() {
        var row = enqueue(configure("feishu"));
        var old = transactions.claim("old-process").orElseThrow();
        assertNotNull(transactions.prepare(old));
        var started = transactions.begin(old);
        assertNotNull(started);
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, old.id())
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).set(BackgroundJobRow::getLeaseUntil, Instant.now().minusSeconds(1)));
        var replacement = transactions.claim("replacement-process").orElseThrow();
        assertTrue(replacement.leaseVersion() > old.leaseVersion());
        assertNotNull(transactions.prepare(replacement));
        var second = transactions.begin(replacement);
        assertNotNull(second);
        transactions.complete(replacement, second.attemptId(), accepted("new-result"));
        transactions.complete(old, started.attemptId(), accepted("old-result"));
        assertEquals("new-result", current(row).getProviderMessageId());
        assertEquals(List.of("unknown", "accepted"), history(row).stream().map(NotificationChannelAttemptRow::getOutcome).toList());
    }

    @Test
    void finalCheckStopsBindingChangeAfterPreparationWithoutContactingPlatform() {
        var row = enqueue(configure("feishu"));
        var lease = transactions.claim("prepared-process").orElseThrow();
        assertNotNull(transactions.prepare(lease));
        bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)
            .eq(UserChannelBindingRow::getId, row.getBindingId()).set(UserChannelBindingRow::getReceiveEnabled, false)
            .set(UserChannelBindingRow::getRevision, row.getBindingRevision() + 1));
        assertNull(transactions.begin(lease));
        assertEquals("cancelled", current(row).getStatus());
        assertEquals(0, current(row).getAttemptCount());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    @Test
    void tokenExpirationRefreshesOnlyOnceForTheSameWork() throws Exception {
        var row = enqueue(configure("feishu"));
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.expiredToken");
        worker.runOnce();
        PLATFORM.take();
        PLATFORM.take();
        due(row);
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.expiredToken");
        worker.runOnce();
        PLATFORM.take();
        PLATFORM.take();
        assertEquals("failed", current(row).getStatus());
        assertEquals(2, current(row).getAttemptCount());
        assertFalse(worker.runOnce());
    }

    @Test
    void platformRateWaitDoesNotEraseTheRequiredWaitingTime() throws Exception {
        var row = enqueue(configure("feishu"));
        PLATFORM.reply("feishu.token");
        PLATFORM.reply(429, "feishu.rateLimit", Map.of("x-ogw-ratelimit-reset", "300"));
        Instant before = Instant.now();
        worker.runOnce();
        PLATFORM.take();
        PLATFORM.take();
        assertEquals("retry_wait", current(row).getStatus());
        assertFalse(current(row).getNextAttemptAt().isBefore(before.plusSeconds(300)));
        assertEquals(1, current(row).getAttemptCount());
    }

    @Test
    void sharedRateLimitsAreAtomicAndRecipientDenialDoesNotSpendTheApplicationQuota() throws Exception {
        var app = queries.application(configure("feishu"));
        var limits = List.of(new ChannelRateLimit("app_test", false, 60, 2, false, 0),
            new ChannelRateLimit("recipient_test", true, 60, 1, false, 0));
        assertEquals(Duration.ZERO, limiter.acquire(app, "member-a", limits));
        assertTrue(limiter.acquire(app, "member-a", limits).isPositive());
        assertEquals(Duration.ZERO, limiter.acquire(app, "member-b", limits));
        assertTrue(limiter.acquire(app, "member-c", limits).isPositive());
        var daily = List.of(new ChannelRateLimit("daily_test", false, 86400, 3, true, 8 * 3600));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 10).mapToObj(index -> workers.submit(() -> limiter.acquire(app, "same-member", daily))).toList();
            int acquired = 0;
            for (var task : tasks) {
                if (task.get().isZero()) {
                    acquired++;
                }
            }
            assertEquals(3, acquired);
        }
    }

    @Test
    void localFrequencyWaitDoesNotCountAsAnAttempt() throws Exception {
        var connection = configure("wecom");
        connections.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .eq(EnterpriseIntegrationRow::getId, connection.getId()).set(EnterpriseIntegrationRow::getConfigJson, "{\"dailyMessageLimit\":1}"));
        var row = enqueue(connection);
        var app = queries.application(connection);
        limiter.acquire(app, "member_001", List.of(new ChannelRateLimit("application_day", false, 86400, 1, true, 8 * 3600)));
        PLATFORM.reply("wecom.token");
        worker.runOnce();
        PLATFORM.take();
        assertEquals(0, PLATFORM.remainingRequests());
        assertEquals(0, current(row).getAttemptCount());
        assertTrue(history(row).isEmpty());
    }

    @Test
    void automaticDeliveryRequiresExplicitPreferenceAndDoesNotReplayOldEvents() {
        var connection = configure("feishu");
        tx.executeWithoutResult(status -> {
            var first = writer.write(enterprise, admin, notice(), null, null).orElseThrow();
            routing.automatic(first.notification(), Instant.now());
        });
        assertEquals(0, deliveries.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
        var preference = new UserChannelPreferenceRow();
        preference.setEnterpriseId(enterprise);
        preference.setUserId(admin);
        preference.setConnectionId(connection.getId());
        preference.setCategory("schedule");
        preference.setEnabled(true);
        preferences.insert(preference);
        tx.executeWithoutResult(status -> {
            var old = writer.write(enterprise, admin, notice(), null, null).orElseThrow();
            routing.automatic(old.notification(), Instant.now().minusSeconds(86401));
            var current = writer.write(enterprise, admin, notice(), null, null).orElseThrow();
            routing.automatic(current.notification(), Instant.now());
        });
        assertEquals(1, deliveries.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
    }

    @Test
    void disablingCancelsWaitingButPreservesAnAlreadyStartedRequest() throws Exception {
        var connection = configure("feishu");
        var pending = enqueue(connection);
        var running = enqueue(connection);
        var lease = transactions.claim("in-flight").orElseThrow();
        var started = transactions.begin(lease);
        assertNotNull(started);
        String working = started.message().deliveryId();
        change(HttpMethod.PATCH, base() + "/integrations/" + connection.getId() + "/status", Map.of("status", "disabled"), "1", UUID.randomUUID().toString())
            .andExpect(status().isOk());
        var waiting = working.equals(pending.getId()) ? running : pending;
        assertEquals("cancelled", current(waiting).getStatus());
        change(HttpMethod.DELETE, base() + "/integrations/" + connection.getId(), null, "2", UUID.randomUUID().toString()).andExpect(status().isConflict());
        transactions.complete(lease, started.attemptId(), accepted("already-started"));
        assertEquals("accepted", deliveries.selectById(working).getStatus());
        change(HttpMethod.PATCH, base() + "/integrations/" + connection.getId() + "/status", Map.of("status", "enabled"), "2", UUID.randomUUID().toString())
            .andExpect(status().isOk());
        assertFalse(worker.runOnce());
        assertEquals("cancelled", current(waiting).getStatus());
    }

    @Test
    void manualRetryAddsOneAttemptAndAnAcceptedResultCannotBeRetried() throws Exception {
        var row = enqueue(configure("feishu"));
        var lease = transactions.claim("failure").orElseThrow();
        var start = transactions.begin(lease);
        transactions.complete(lease, start.attemptId(), new ChannelSendResult(ChannelSendResult.Outcome.PERMANENT_FAILURE,
            "230027", null, "CHANNEL_PERMISSION_DENIED", "平台未授予发送权限。", null, 400, null));
        String path = base() + "/notification-deliveries/" + row.getId() + "/retry";
        String key = UUID.randomUUID().toString(), revision = current(row).getRevision().toString();
        var first = data(change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), revision, key).andExpect(status().isOk()).andReturn());
        assertEquals(first, data(change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), revision, key).andExpect(status().isOk()).andReturn()));
        assertEquals(2, current(row).getMaxAttempts());
        assertEquals(1, current(row).getManualRetryCount());
        var next = transactions.claim("manual").orElseThrow();
        var second = transactions.begin(next);
        transactions.complete(next, second.attemptId(), accepted("after-manual-retry"));
        change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), current(row).getRevision().toString(), UUID.randomUUID().toString())
            .andExpect(status().isConflict());
    }

    @Test
    void unknownRequestRequiresExplicitDuplicateAcknowledgementAndCancellationKeepsItsUncertainty() throws Exception {
        var connection = configure("feishu");
        var row = enqueue(connection);
        var lease = transactions.claim("uncertain").orElseThrow();
        var start = transactions.begin(lease);
        transactions.complete(lease, start.attemptId(), new ChannelSendResult(ChannelSendResult.Outcome.UNKNOWN, null, null,
            "CHANNEL_NETWORK", "未取得响应。", null, null, null));
        change(HttpMethod.PATCH, base() + "/integrations/" + connection.getId() + "/status", Map.of("status", "disabled"), "1", UUID.randomUUID().toString())
            .andExpect(status().isOk());
        assertEquals("unknown", current(row).getStatus());
        change(HttpMethod.PATCH, base() + "/integrations/" + connection.getId() + "/status", Map.of("status", "enabled"), "2", UUID.randomUUID().toString())
            .andExpect(status().isOk());
        String path = base() + "/notification-deliveries/" + row.getId() + "/retry";
        change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", false), current(row).getRevision().toString(), UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
        change(HttpMethod.POST, path, Map.of("confirmMayDuplicate", true), current(row).getRevision().toString(), UUID.randomUUID().toString())
            .andExpect(status().isOk());
        assertEquals(2, current(row).getMaxAttempts());
    }

    @Test
    void preferenceAndTestMessageApisUseVersionsAndDoNotExposePayloads() throws Exception {
        var connection = configure("feishu");
        String preferencePath = base() + "/me/channel-preferences";
        var options = data(mvc.perform(get(preferencePath).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertFalse(options.at("/0/categories/0/enabled").asBoolean());
        var choice = Map.of("connectionId", connection.getId(), "category", "schedule", "enabled", true);
        change(HttpMethod.PUT, preferencePath, choice, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        change(HttpMethod.PUT, preferencePath, choice, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        String path = base() + "/integrations/" + connection.getId();
        var recipients = data(mvc.perform(get(path + "/recipients").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(admin, recipients.at("/items/0/id").asText());
        String key = UUID.randomUUID().toString();
        var first = data(change(HttpMethod.POST, path + "/test-messages", Map.of("recipientUserId", admin), "1", key)
            .andExpect(status().isCreated()).andReturn());
        assertEquals(first, data(change(HttpMethod.POST, path + "/test-messages", Map.of("recipientUserId", admin), "1", key)
            .andExpect(status().isCreated()).andReturn()));
        assertFalse(first.toString().contains("payloadJson"));
        assertFalse(first.toString().contains("ou_test_member"));
        mvc.perform(get(base() + "/notifications/" + first.at("/delivery/notificationId").asText()).cookie(cookie)).andExpect(status().isOk());
    }

    private EnterpriseIntegrationRow configure(String provider) {
        return new ChannelDeliveryFixtures(tx, secrets, queries, connections, bindings).configure(enterprise, admin, provider);
    }

    @Test
    void retentionPreservesBusinessNoticesAndFailureReminderNeverCreatesAnExternalLoop() {
        var row = enqueue(configure("feishu"));
        var lease = transactions.claim("retention-test").orElseThrow();
        var start = transactions.begin(lease);
        transactions.complete(lease, start.attemptId(), new ChannelSendResult(ChannelSendResult.Outcome.PERMANENT_FAILURE, "230027", null,
            "CHANNEL_PERMISSION_DENIED", "没有发送权限。", null, 400, null));
        for (var candidate : inApp.candidates()) {
            inApp.deliver(candidate);
        }
        assertTrue(noticeRows.exists(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
            .eq(NotificationRow::getEventKey, "channel-failure:" + row.getId())));
        assertEquals(1, deliveries.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
        Instant old = Instant.now().minus(Duration.ofDays(181));
        deliveries.update(new LambdaUpdateWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getId, row.getId())
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).set(NotificationChannelDeliveryRow::getExpiresAt, old)
            .set(NotificationChannelDeliveryRow::getUpdatedAt, Instant.now().minus(Duration.ofDays(31))));
        retention.sweep();
        assertNull(current(row).getPayloadJson());
        assertEquals(1, history(row).size());
        deliveries.update(new LambdaUpdateWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getId, row.getId())
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).set(NotificationChannelDeliveryRow::getUpdatedAt, old));
        attempts.update(new LambdaUpdateWrapper<NotificationChannelAttemptRow>().eq(NotificationChannelAttemptRow::getDeliveryId, row.getId())
            .eq(NotificationChannelAttemptRow::getEnterpriseId, enterprise).set(NotificationChannelAttemptRow::getFinishedAt, old));
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, row.getActiveJobId())
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).set(BackgroundJobRow::getUpdatedAt, old));
        retention.sweep();
        assertNull(current(row));
        assertTrue(history(row).isEmpty());
        assertNull(jobs.selectById(row.getActiveJobId()));
        assertNotNull(noticeRows.selectById(row.getNotificationId()));
        retention.sweep();
    }

    private NotificationChannelDeliveryRow enqueue(EnterpriseIntegrationRow connection) {
        return enqueue(connection, notice());
    }

    private NotificationChannelDeliveryRow enqueue(EnterpriseIntegrationRow connection, Notice notice) {
        return tx.execute(status -> {
            var written = writer.write(enterprise, admin, notice, null, admin).orElseThrow();
            return routing.enqueue(written.notification(), policy.capture(enterprise, admin, connection), "test", Instant.now().plusSeconds(3600));
        });
    }

    private Notice notice() {
        return new Notice("channel:" + UUID.randomUUID(), "schedule", "测试通知", "这是发送测试。", null, null);
    }

    private void due(NotificationChannelDeliveryRow row) {
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getId, current(row).getActiveJobId()).set(BackgroundJobRow::getAvailableAt, Instant.now().minusSeconds(1)));
    }

    private NotificationChannelDeliveryRow current(NotificationChannelDeliveryRow row) {
        return deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)
            .eq(NotificationChannelDeliveryRow::getId, row.getId()));
    }

    private List<NotificationChannelAttemptRow> history(NotificationChannelDeliveryRow row) {
        return attempts.selectList(new LambdaQueryWrapper<NotificationChannelAttemptRow>().eq(NotificationChannelAttemptRow::getEnterpriseId, enterprise)
            .eq(NotificationChannelAttemptRow::getDeliveryId, row.getId()).orderByAsc(NotificationChannelAttemptRow::getAttemptNo));
    }

    private ChannelSendResult accepted(String message) {
        return new ChannelSendResult(ChannelSendResult.Outcome.ACCEPTED, "0", message, null, null, null, 200, "test-trace");
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动通知模拟平台", failure);
        }
    }
}
