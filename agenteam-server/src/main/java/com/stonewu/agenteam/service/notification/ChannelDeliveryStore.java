package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelAttemptMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelAttemptRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** 发送状态与尝试记录只在持有企业锁和当前工作租约的事务内修改。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelDeliveryStore {
    public static final Set<String> ACTIVE = Set.of("pending", "sending", "retry_wait");
    private final EnterpriseMapper enterprises;
    private final NotificationChannelDeliveryMapper deliveries;
    private final NotificationChannelAttemptMapper attempts;
    private final NotificationSqlMapper notifications;
    private final BackgroundJobMapper jobs;
    private final ChannelPayloadCodec payloads;
    private final Clock clock;

    public ChannelDeliveryStore(EnterpriseMapper enterprises, NotificationChannelDeliveryMapper deliveries,
                                NotificationChannelAttemptMapper attempts, NotificationSqlMapper notifications,
                                BackgroundJobMapper jobs, ChannelPayloadCodec payloads, Clock clock) {
        this.enterprises = enterprises;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.notifications = notifications;
        this.jobs = jobs;
        this.payloads = payloads;
        this.clock = clock;
    }

    public Owned owned(JobLease lease) {
        var payload = payloads.decode(lease.payloadJson());
        String enterpriseStatus = enterprises.lockEnterprise(lease.enterpriseId()).orElse("missing");
        var row = deliveries.lock(lease.enterpriseId(), payload.path("deliveryId").asText());
        if (!jobs.lockOwned(lease, clock.instant())) {
            return null;
        }
        if (row == null || !lease.id().equals(row.getActiveJobId()) || row.getDispatchGeneration() != payload.path("generation").asInt()) {
            jobs.finish(lease, "cancelled", "CHANNEL_WORK_SUPERSEDED", "此发送工作已被后续操作取代。", clock.instant(), clock.instant());
            return null;
        }
        var notice = notifications.selectOne(new LambdaQueryWrapper<NotificationRow>()
            .eq(NotificationRow::getEnterpriseId, row.getEnterpriseId()).eq(NotificationRow::getId, row.getNotificationId())
            .eq(NotificationRow::getUserId, row.getRecipientUserId()));
        return new Owned(row, notice, "active".equals(enterpriseStatus));
    }

    public void save(NotificationChannelDeliveryRow row) {
        var changes = new LambdaUpdateWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, row.getEnterpriseId()).eq(NotificationChannelDeliveryRow::getId, row.getId())
            .eq(NotificationChannelDeliveryRow::getRevision, row.getRevision())
            .set(NotificationChannelDeliveryRow::getStatus, row.getStatus()).set(NotificationChannelDeliveryRow::getAttemptCount, row.getAttemptCount())
            .set(NotificationChannelDeliveryRow::getMaxAttempts, row.getMaxAttempts()).set(NotificationChannelDeliveryRow::getManualRetryCount, row.getManualRetryCount())
            .set(NotificationChannelDeliveryRow::getDispatchGeneration, row.getDispatchGeneration()).set(NotificationChannelDeliveryRow::getActiveJobId, row.getActiveJobId())
            .set(NotificationChannelDeliveryRow::getNextAttemptAt, row.getNextAttemptAt()).set(NotificationChannelDeliveryRow::getFirstSendAt, row.getFirstSendAt())
            .set(NotificationChannelDeliveryRow::getRetryDeadlineAt, row.getRetryDeadlineAt()).set(NotificationChannelDeliveryRow::getAcceptedAt, row.getAcceptedAt())
            .set(NotificationChannelDeliveryRow::getProviderMessageId, row.getProviderMessageId()).set(NotificationChannelDeliveryRow::getLastErrorCode, row.getLastErrorCode())
            .set(NotificationChannelDeliveryRow::getLastErrorSummary, row.getLastErrorSummary()).set(NotificationChannelDeliveryRow::getUpdatedAt, clock.instant())
            .set(NotificationChannelDeliveryRow::getRevision, row.getRevision() + 1);
        if (deliveries.update(changes) != 1) {
            throw new IllegalStateException("通知发送记录在保存期间发生变化");
        }
        row.setRevision(row.getRevision() + 1);
    }

    public boolean uncertain(NotificationChannelDeliveryRow row) {
        return attempts.exists(scope(row).in(NotificationChannelAttemptRow::getOutcome, "started", "unknown"));
    }

    public boolean tokenAlreadyRefreshed(NotificationChannelDeliveryRow row, JobLease lease) {
        return attempts.exists(scope(row).eq(NotificationChannelAttemptRow::getBackgroundJobId, lease.id())
            .eq(NotificationChannelAttemptRow::getErrorCode, "CHANNEL_TOKEN_EXPIRED"));
    }

    public void recoverStarted(NotificationChannelDeliveryRow row) {
        attempts.update(new LambdaUpdateWrapper<NotificationChannelAttemptRow>()
            .eq(NotificationChannelAttemptRow::getEnterpriseId, row.getEnterpriseId()).eq(NotificationChannelAttemptRow::getDeliveryId, row.getId())
            .eq(NotificationChannelAttemptRow::getOutcome, "started").set(NotificationChannelAttemptRow::getOutcome, "unknown")
            .set(NotificationChannelAttemptRow::getErrorCode, "CHANNEL_LEASE_EXPIRED")
            .set(NotificationChannelAttemptRow::getErrorSummary, "上一次请求未保存最终结果，可能已被平台接受。")
            .set(NotificationChannelAttemptRow::getFinishedAt, clock.instant()));
    }

    public NotificationChannelAttemptRow start(NotificationChannelDeliveryRow row, JobLease lease) {
        var attempt = new NotificationChannelAttemptRow();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setEnterpriseId(row.getEnterpriseId());
        attempt.setDeliveryId(row.getId());
        attempt.setAttemptNo(row.getAttemptCount() + 1);
        attempt.setBackgroundJobId(lease.id());
        attempt.setLeaseVersion(lease.leaseVersion());
        attempt.setOutcome("started");
        attempt.setStartedAt(clock.instant());
        attempts.insert(attempt);
        row.setAttemptCount(attempt.getAttemptNo());
        return attempt;
    }

    public boolean finishAttempt(NotificationChannelDeliveryRow row, JobLease lease, String attemptId, ChannelSendResult result) {
        String outcome = switch (result.outcome()) {
            case ACCEPTED -> "accepted";
            case RETRYABLE_FAILURE, TOKEN_EXPIRED -> "retryable_failure";
            case PERMANENT_FAILURE -> "permanent_failure";
            case UNKNOWN -> "unknown";
        };
        return attempts.update(new LambdaUpdateWrapper<NotificationChannelAttemptRow>()
            .eq(NotificationChannelAttemptRow::getEnterpriseId, row.getEnterpriseId()).eq(NotificationChannelAttemptRow::getDeliveryId, row.getId())
            .eq(NotificationChannelAttemptRow::getId, attemptId).eq(NotificationChannelAttemptRow::getBackgroundJobId, lease.id())
            .eq(NotificationChannelAttemptRow::getLeaseVersion, lease.leaseVersion()).eq(NotificationChannelAttemptRow::getOutcome, "started")
            .set(NotificationChannelAttemptRow::getOutcome, outcome).set(NotificationChannelAttemptRow::getHttpStatus, result.httpStatus())
            .set(NotificationChannelAttemptRow::getProviderCode, result.providerCode()).set(NotificationChannelAttemptRow::getProviderRequestTrace, result.traceId())
            .set(NotificationChannelAttemptRow::getErrorCode, result.outcome() == ChannelSendResult.Outcome.TOKEN_EXPIRED ? "CHANNEL_TOKEN_EXPIRED" : result.errorCode())
            .set(NotificationChannelAttemptRow::getErrorSummary, result.summary()).set(NotificationChannelAttemptRow::getFinishedAt, clock.instant())) == 1;
    }

    private LambdaQueryWrapper<NotificationChannelAttemptRow> scope(NotificationChannelDeliveryRow row) {
        return new LambdaQueryWrapper<NotificationChannelAttemptRow>().eq(NotificationChannelAttemptRow::getEnterpriseId, row.getEnterpriseId())
            .eq(NotificationChannelAttemptRow::getDeliveryId, row.getId());
    }

    public record Owned(NotificationChannelDeliveryRow delivery, NotificationRow notice, boolean activeEnterprise) {
    }
}
