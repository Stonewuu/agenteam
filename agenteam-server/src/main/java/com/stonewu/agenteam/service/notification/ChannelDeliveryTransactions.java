package com.stonewu.agenteam.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.integration.IntegrationConfigurationMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** 领取、准备、请求开始和结果保存分别提交短事务，外部调用不能持有数据库锁。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChannelDeliveryTransactions {
    private final BackgroundJobMapper jobs;
    private final ChannelDeliveryStore store;
    private final ChannelDeliveryPolicy policy;
    private final IntegrationQueryService queries;
    private final IntegrationConfigurationMapper configurations;
    private final IntegrationProviderRegistry providers;
    private final ChannelPayloadCodec payloads;
    private final Clock clock;
    private final ChannelFailureNoticeService failures;

    public ChannelDeliveryTransactions(BackgroundJobMapper jobs, ChannelDeliveryStore store, ChannelDeliveryPolicy policy,
                                        IntegrationQueryService queries, IntegrationConfigurationMapper configurations,
                                        IntegrationProviderRegistry providers, ChannelPayloadCodec payloads, Clock clock,
                                        ChannelFailureNoticeService failures) {
        this.jobs = jobs;
        this.store = store;
        this.policy = policy;
        this.queries = queries;
        this.configurations = configurations;
        this.providers = providers;
        this.payloads = payloads;
        this.clock = clock;
        this.failures = failures;
    }

    public Optional<JobLease> claim(String worker) {
        return jobs.claim("channel_delivery", worker, clock.instant());
    }

    public boolean renew(JobLease lease) {
        return jobs.renew(lease, clock.instant());
    }

    public Prepared prepare(JobLease lease) {
        var owned = store.owned(lease);
        if (owned == null) {
            return null;
        }
        var row = owned.delivery();
        if ("sending".equals(row.getStatus())) {
            store.recoverStarted(row);
        }
        return ready(owned, lease);
    }

    public Started begin(JobLease lease) {
        var owned = store.owned(lease);
        if (owned == null) {
            return null;
        }
        // 同一租约的 begin 不能重复调用后创建第二个网络请求。
        if ("sending".equals(owned.delivery().getStatus())) {
            return null;
        }
        var prepared = ready(owned, lease);
        if (prepared == null) {
            return null;
        }
        var row = owned.delivery();
        if (row.getFirstSendAt() == null) {
            row.setFirstSendAt(clock.instant());
            Duration window = providers.sender(prepared.application().providerCode()).duplicateCheckWindow();
            Instant deadline = clock.instant().plus(window.compareTo(Duration.ofMinutes(10)) > 0 ? window.minusMinutes(10) : Duration.ZERO);
            row.setRetryDeadlineAt(deadline.isBefore(row.getExpiresAt()) ? deadline : row.getExpiresAt());
        }
        var attempt = store.start(row, lease);
        row.setStatus("sending");
        row.setNextAttemptAt(null);
        store.save(row);
        return new Started(attempt.getId(), prepared);
    }

    public void complete(JobLease lease, String attemptId, ChannelSendResult result) {
        var owned = store.owned(lease);
        if (owned == null || !"sending".equals(owned.delivery().getStatus())) {
            return;
        }
        var row = owned.delivery();
        boolean refreshed = store.tokenAlreadyRefreshed(row, lease);
        if (!store.finishAttempt(row, lease, attemptId, result)) {
            return;
        }
        if (result.outcome() == ChannelSendResult.Outcome.ACCEPTED) {
            row.setAcceptedAt(clock.instant());
            row.setProviderMessageId(result.messageId());
            finish(row, lease, "accepted", null, null);
            return;
        }
        if (result.outcome() == ChannelSendResult.Outcome.PERMANENT_FAILURE
            || result.outcome() == ChannelSendResult.Outcome.TOKEN_EXPIRED && refreshed) {
            finish(row, lease, "failed", result.errorCode(), result.summary());
            return;
        }
        var cancelled = policy.sourceBlock(owned.notice());
        if (cancelled != null) {
            finish(row, lease, cancelled.status(), cancelled.code(), cancelled.summary());
            return;
        }
        if (row.getAttemptCount() >= row.getMaxAttempts()) {
            finish(row, lease, "failed", "CHANNEL_ATTEMPTS_EXHAUSTED", "通知发送已用尽本次允许的尝试次数。");
            return;
        }
        long[] backoff = {30, 120, 600, 1800};
        long seconds = result.outcome() == ChannelSendResult.Outcome.TOKEN_EXPIRED ? 1
            : backoff[Math.min(row.getAttemptCount() - 1, backoff.length - 1)] + ThreadLocalRandom.current().nextLong(6);
        if (result.retryAfterSeconds() != null) {
            seconds = Math.max(seconds, result.retryAfterSeconds());
        }
        waitUntil(row, lease, clock.instant().plusSeconds(seconds), result.errorCode(), result.summary());
    }

    public void defer(JobLease lease, Duration delay, String code, String summary) {
        var owned = store.owned(lease);
        if (owned != null && ChannelDeliveryStore.ACTIVE.contains(owned.delivery().getStatus())) {
            if ("sending".equals(owned.delivery().getStatus())) {
                // 请求开始后的异常必须通过 complete 保存为结果未知，不能伪装成发送前等待。
                throw new IllegalStateException("已经开始的通知请求不能退回发送前等待");
            }
            waitUntil(owned.delivery(), lease, clock.instant().plus(delay), code, summary);
        }
    }

    private Prepared ready(ChannelDeliveryStore.Owned owned, JobLease lease) {
        var row = owned.delivery();
        if (!ChannelDeliveryStore.ACTIVE.contains(row.getStatus())) {
            jobs.finish(lease, "accepted".equals(row.getStatus()) ? "completed" : "cancelled", row.getLastErrorCode(),
                row.getLastErrorSummary(), clock.instant(), clock.instant());
            return null;
        }
        if (lease.exhausted() || row.getAttemptCount() >= row.getMaxAttempts()) {
            finish(row, lease, "failed", "CHANNEL_RECOVERY_EXHAUSTED", "通知发送或恢复次数已达到上限。");
            return null;
        }
        if (!row.getExpiresAt().isAfter(clock.instant())) {
            finish(row, lease, "expired", "NOTIFICATION_EXPIRED", "此通知已超过发送有效期。");
            return null;
        }
        if (!safeRetry(row, clock.instant())) {
            finish(row, lease, "unknown", "CHANNEL_RESULT_UNKNOWN", "此前请求可能已被接受，已停止自动重试。");
            return null;
        }
        if (!owned.activeEnterprise() || owned.notice() == null) {
            finish(row, lease, "blocked", "ENTERPRISE_UNAVAILABLE", "企业或通知当前不可用。");
            return null;
        }
        var connection = policy.connection(row.getEnterpriseId(), row.getConnectionId());
        var binding = policy.binding(row.getEnterpriseId(), row.getConnectionId(), row.getRecipientUserId(), row.getBindingId());
        var target = new ChannelDeliveryTarget(row.getConnectionId(), row.getCredentialRevision(), row.getBindingId(), row.getBindingRevision());
        var blocked = policy.check(owned.notice(), target, row.getDeliveryReason(), connection, binding);
        if (blocked != null) {
            finish(row, lease, blocked.status(), blocked.code(), blocked.summary());
            return null;
        }
        if (row.getPayloadJson() == null || !payloads.hash(payloads.decode(row.getPayloadJson())).equals(row.getPayloadHash())) {
            finish(row, lease, "failed", "CHANNEL_CONTENT_UNAVAILABLE", "原发送内容不可用，不能继续发送。");
            return null;
        }
        if ("sending".equals(row.getStatus())) {
            row.setStatus("retry_wait");
            store.save(row);
        }
        return new Prepared(row.getId(), queries.application(connection), binding.getExternalSubjectId(), payloads.decode(row.getPayloadJson()),
            providers.sender(connection.getProviderCode()).rateLimits(configurations.read(connection)));
    }

    private boolean safeRetry(NotificationChannelDeliveryRow row, Instant at) {
        return !store.uncertain(row) || row.getDispatchGeneration() > 1 && row.getAttemptCount() < row.getMaxAttempts()
            || row.getRetryDeadlineAt() != null && row.getRetryDeadlineAt().isAfter(at);
    }

    private void waitUntil(NotificationChannelDeliveryRow row, JobLease lease, Instant next, String code, String summary) {
        if (!next.isBefore(row.getExpiresAt()) || !safeRetry(row, next)) {
            finish(row, lease, "expired", "NOTIFICATION_RETRY_EXPIRED", "在通知有效期内无法继续等待发送。");
            return;
        }
        row.setStatus("retry_wait");
        row.setNextAttemptAt(next);
        row.setLastErrorCode(code);
        row.setLastErrorSummary(summary);
        store.save(row);
        requireFinished(jobs.finish(lease, "queued", code, summary, next, clock.instant()));
    }

    private void finish(NotificationChannelDeliveryRow row, JobLease lease, String status, String code, String summary) {
        boolean uncertain = !"accepted".equals(status) && store.uncertain(row);
        row.setStatus(uncertain ? "unknown" : status);
        row.setNextAttemptAt(null);
        row.setLastErrorCode(code);
        row.setLastErrorSummary(uncertain ? "此前请求可能已被平台接受；" + (summary == null ? "最终结果无法确认。" : summary) : summary);
        store.save(row);
        String jobStatus = "accepted".equals(row.getStatus()) ? "completed" : "cancelled".equals(row.getStatus()) ? "cancelled" : "failed";
        requireFinished(jobs.finish(lease, jobStatus, code, row.getLastErrorSummary(), clock.instant(), clock.instant()));
        failures.enqueue(row);
    }

    private void requireFinished(boolean finished) {
        if (!finished) {
            throw new IllegalStateException("通知工作租约已变化，不能保存旧结果");
        }
    }

    public record Prepared(String deliveryId, IntegrationApplication application, String recipient, JsonNode payload,
                           List<ChannelRateLimit> rateLimits) {
        @Override
        public String toString() {
            return "Prepared[deliveryId=" + deliveryId + "]";
        }
    }

    public record Started(String attemptId, Prepared message) {
    }
}
