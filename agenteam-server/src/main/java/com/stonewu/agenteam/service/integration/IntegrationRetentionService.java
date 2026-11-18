package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelAttemptMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelAttemptRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.service.notification.ChannelDeliveryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 分批清理已过期的临时材料与终态发送历史，站内通知和业务事件去重记录保留原策略。 */
@Service
public class IntegrationRetentionService {
    private static final Set<String> OAUTH_ACTIVE = Set.of("pending", "processing", "awaiting_confirmation");
    private final ChannelOauthSessionMapper oauth;
    private final NotificationChannelDeliveryMapper deliveries;
    private final NotificationChannelAttemptMapper attempts;
    private final BackgroundJobSqlMapper jobs;
    private final Clock clock;
    private final int oauthDays;
    private final int payloadDays;
    private final int attemptDays;
    private final int resultDays;
    private final RuntimeSettingsStore runtimeSettings;

    public IntegrationRetentionService(ChannelOauthSessionMapper oauth, NotificationChannelDeliveryMapper deliveries,
                                        NotificationChannelAttemptMapper attempts, BackgroundJobSqlMapper jobs, Clock clock,
                                        @Value("${agenteam.integration.retention.oauth-days:7}") int oauthDays,
                                        @Value("${agenteam.integration.retention.payload-days:30}") int payloadDays,
                                        @Value("${agenteam.integration.retention.attempt-days:90}") int attemptDays,
                                        @Value("${agenteam.integration.retention.result-days:180}") int resultDays,
                                        RuntimeSettingsStore runtimeSettings) {
        this.runtimeSettings = runtimeSettings;
        if (!LimitValue.validInteger(oauthDays, 1) || !LimitValue.validInteger(payloadDays, 1)
            || !LimitValue.validInteger(attemptDays, 1) || !LimitValue.validInteger(resultDays, 180)
            || !LimitValue.ordered(payloadDays, attemptDays) || !LimitValue.ordered(attemptDays, resultDays)) {
            throw new IllegalStateException(Messages.text("error.integration.integrationRetentionService.init.1"));
        }
        this.oauth = oauth;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.jobs = jobs;
        this.clock = clock;
        this.oauthDays = oauthDays;
        this.payloadDays = payloadDays;
        this.attemptDays = attemptDays;
        this.resultDays = resultDays;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void sweep() {
        Instant now = clock.instant();
        expireAuthorizations(now);
        clearPayloads(now);
        removeAttempts(now);
        removeResults(now);
        removeUnreferencedJobs(now);
    }

    private void expireAuthorizations(Instant now) {
        var expired = oauth.selectPage(new Page<ChannelOauthSessionRow>(1, 200, false),
            new LambdaQueryWrapper<ChannelOauthSessionRow>().in(ChannelOauthSessionRow::getStatus, OAUTH_ACTIVE)
                .and(group -> group.le(ChannelOauthSessionRow::getExpiresAt, now).or(confirm -> confirm
                    .eq(ChannelOauthSessionRow::getStatus, "awaiting_confirmation").le(ChannelOauthSessionRow::getConfirmationExpiresAt, now)))
                .orderByAsc(ChannelOauthSessionRow::getExpiresAt, ChannelOauthSessionRow::getId)).getRecords();
        if (!expired.isEmpty()) {
            oauth.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().in(ChannelOauthSessionRow::getId, expired.stream().map(ChannelOauthSessionRow::getId).toList())
                .in(ChannelOauthSessionRow::getStatus, OAUTH_ACTIVE)
                .and(group -> group.le(ChannelOauthSessionRow::getExpiresAt, now).or(confirm -> confirm
                    .eq(ChannelOauthSessionRow::getStatus, "awaiting_confirmation").le(ChannelOauthSessionRow::getConfirmationExpiresAt, now)))
                .set(ChannelOauthSessionRow::getStatus, "expired").set(ChannelOauthSessionRow::getTransientEncryptedJson, null)
                .set(ChannelOauthSessionRow::getConfirmationTokenHash, null).set(ChannelOauthSessionRow::getResultCode, "AUTHORIZATION_EXPIRED")
                .set(ChannelOauthSessionRow::getUpdatedAt, now));
        }
        int days = retentionDays(RuntimeSetting.AUTH_RETENTION_DAYS, oauthDays);
        if (LimitValue.unlimited(days)) {
            return;
        }
        Instant cutoff = before(now, days);
        var old = oauth.selectPage(new Page<ChannelOauthSessionRow>(1, 200, false), new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .notIn(ChannelOauthSessionRow::getStatus, OAUTH_ACTIVE).lt(ChannelOauthSessionRow::getUpdatedAt, cutoff)
            .orderByAsc(ChannelOauthSessionRow::getUpdatedAt, ChannelOauthSessionRow::getId)).getRecords();
        if (!old.isEmpty()) {
            oauth.delete(new LambdaQueryWrapper<ChannelOauthSessionRow>().in(ChannelOauthSessionRow::getId, old.stream().map(ChannelOauthSessionRow::getId).toList())
                .notIn(ChannelOauthSessionRow::getStatus, OAUTH_ACTIVE).lt(ChannelOauthSessionRow::getUpdatedAt, cutoff));
        }
    }

    private void clearPayloads(Instant now) {
        int days = retentionDays(RuntimeSetting.PAYLOAD_RETENTION_DAYS, payloadDays);
        if (LimitValue.unlimited(days)) {
            return;
        }
        Instant cutoff = before(now, days);
        var selected = deliveries.selectPage(new Page<NotificationChannelDeliveryRow>(1, 200, false),
            new LambdaQueryWrapper<NotificationChannelDeliveryRow>().notIn(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE)
                .lt(NotificationChannelDeliveryRow::getExpiresAt, now).lt(NotificationChannelDeliveryRow::getUpdatedAt, cutoff)
                .isNotNull(NotificationChannelDeliveryRow::getPayloadJson).orderByAsc(NotificationChannelDeliveryRow::getUpdatedAt, NotificationChannelDeliveryRow::getId)).getRecords();
        if (!selected.isEmpty()) {
            deliveries.update(new LambdaUpdateWrapper<NotificationChannelDeliveryRow>().in(NotificationChannelDeliveryRow::getId,
                    selected.stream().map(NotificationChannelDeliveryRow::getId).toList())
                .notIn(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE)
                .lt(NotificationChannelDeliveryRow::getExpiresAt, now).lt(NotificationChannelDeliveryRow::getUpdatedAt, cutoff)
                .set(NotificationChannelDeliveryRow::getPayloadJson, null));
        }
    }

    private void removeAttempts(Instant now) {
        int days = retentionDays(RuntimeSetting.ATTEMPT_RETENTION_DAYS, attemptDays);
        if (LimitValue.unlimited(days)) {
            return;
        }
        Instant cutoff = before(now, days);
        var query = JoinWrappers.lambda(NotificationChannelAttemptRow.class).selectAll(NotificationChannelAttemptRow.class)
            .innerJoin(NotificationChannelDeliveryRow.class, on -> on.eq(NotificationChannelDeliveryRow::getEnterpriseId, NotificationChannelAttemptRow::getEnterpriseId)
                .eq(NotificationChannelDeliveryRow::getId, NotificationChannelAttemptRow::getDeliveryId))
            .notIn(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE).lt(NotificationChannelDeliveryRow::getExpiresAt, now)
            .lt(NotificationChannelAttemptRow::getFinishedAt, cutoff).ne(NotificationChannelAttemptRow::getOutcome, "started")
            .orderByAsc(NotificationChannelAttemptRow::getFinishedAt, NotificationChannelAttemptRow::getId);
        var selected = attempts.selectJoinPage(new Page<NotificationChannelAttemptRow>(1, 200, false), NotificationChannelAttemptRow.class, query).getRecords();
        if (!selected.isEmpty()) {
            attempts.delete(new LambdaQueryWrapper<NotificationChannelAttemptRow>().in(NotificationChannelAttemptRow::getId,
                selected.stream().map(NotificationChannelAttemptRow::getId).toList()).ne(NotificationChannelAttemptRow::getOutcome, "started")
                .lt(NotificationChannelAttemptRow::getFinishedAt, cutoff));
        }
    }

    private void removeResults(Instant now) {
        int days = retentionDays(RuntimeSetting.RESULT_RETENTION_DAYS, resultDays);
        if (LimitValue.unlimited(days)) {
            return;
        }
        Instant cutoff = before(now, days);
        var query = JoinWrappers.lambda(NotificationChannelDeliveryRow.class).selectAll(NotificationChannelDeliveryRow.class)
            .leftJoin(NotificationChannelAttemptRow.class, on -> on.eq(NotificationChannelAttemptRow::getEnterpriseId, NotificationChannelDeliveryRow::getEnterpriseId)
                .eq(NotificationChannelAttemptRow::getDeliveryId, NotificationChannelDeliveryRow::getId))
            .isNull(NotificationChannelAttemptRow::getId).notIn(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE)
            .lt(NotificationChannelDeliveryRow::getExpiresAt, now).lt(NotificationChannelDeliveryRow::getUpdatedAt, cutoff)
            .isNull(NotificationChannelDeliveryRow::getPayloadJson).orderByAsc(NotificationChannelDeliveryRow::getUpdatedAt, NotificationChannelDeliveryRow::getId);
        var selected = deliveries.selectJoinPage(new Page<NotificationChannelDeliveryRow>(1, 200, false), NotificationChannelDeliveryRow.class, query).getRecords();
        if (!selected.isEmpty()) {
            deliveries.delete(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().in(NotificationChannelDeliveryRow::getId,
                    selected.stream().map(NotificationChannelDeliveryRow::getId).toList())
                .notIn(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE).lt(NotificationChannelDeliveryRow::getExpiresAt, now)
                .lt(NotificationChannelDeliveryRow::getUpdatedAt, cutoff));
        }
    }

    private void removeUnreferencedJobs(Instant now) {
        int days = retentionDays(RuntimeSetting.RESULT_RETENTION_DAYS, resultDays);
        if (LimitValue.unlimited(days)) {
            return;
        }
        Instant cutoff = before(now, days);
        var query = JoinWrappers.lambda(BackgroundJobRow.class).selectAll(BackgroundJobRow.class)
            .leftJoin(NotificationChannelDeliveryRow.class, on -> on.eq(NotificationChannelDeliveryRow::getEnterpriseId, BackgroundJobRow::getEnterpriseId)
                .eq(NotificationChannelDeliveryRow::getActiveJobId, BackgroundJobRow::getId))
            .leftJoin(NotificationChannelAttemptRow.class, on -> on.eq(NotificationChannelAttemptRow::getEnterpriseId, BackgroundJobRow::getEnterpriseId)
                .eq(NotificationChannelAttemptRow::getBackgroundJobId, BackgroundJobRow::getId))
            .eq(BackgroundJobRow::getKind, "channel_delivery").in(BackgroundJobRow::getStatus, List.of("completed", "failed", "cancelled"))
            .lt(BackgroundJobRow::getUpdatedAt, cutoff).isNull(NotificationChannelDeliveryRow::getId)
            .isNull(NotificationChannelAttemptRow::getId).orderByAsc(BackgroundJobRow::getUpdatedAt, BackgroundJobRow::getId);
        var selected = jobs.selectJoinPage(new Page<BackgroundJobRow>(1, 200, false), BackgroundJobRow.class, query).getRecords();
        if (!selected.isEmpty()) {
            jobs.delete(new LambdaQueryWrapper<BackgroundJobRow>().in(BackgroundJobRow::getId, selected.stream().map(BackgroundJobRow::getId).toList())
                .eq(BackgroundJobRow::getKind, "channel_delivery").in(BackgroundJobRow::getStatus, "completed", "failed", "cancelled")
                .lt(BackgroundJobRow::getUpdatedAt, cutoff));
        }
    }

    private int retentionDays(RuntimeSetting setting, int fallback) {
        return runtimeSettings == null ? fallback : Math.toIntExact(runtimeSettings.value(setting, fallback));
    }

    private Instant before(Instant now, int days) {
        var cutoff = now.minus(Duration.ofDays(days));
        var earliest = Instant.parse("1000-01-01T00:00:00Z");
        return cutoff.isBefore(earliest) ? earliest : cutoff;
    }
}
