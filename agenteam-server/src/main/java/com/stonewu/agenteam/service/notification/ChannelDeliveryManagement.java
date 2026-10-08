package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.integration.request.IntegrationTestMessageRequest;
import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;
import com.stonewu.agenteam.model.notification.request.ChannelDeliveryRetryRequest;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryDetail;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.schedule.ScheduledNotificationRetryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 测试通知和人工重试都只排队；页面提交不会同步等待平台网络。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChannelDeliveryManagement {
    private final EnterpriseMapper enterprises;
    private final NotificationChannelDeliveryMapper deliveries;
    private final ChannelDeliveryStore store;
    private final ChannelDeliveryPolicy policy;
    private final ChannelDeliveryAccess access;
    private final ChannelDeliveryQueryService queries;
    private final ChannelNotificationRouting routing;
    private final NotificationWriteService writer;
    private final IntegrationManagementPolicy integrationPolicy;
    private final IntegrationQueryService integrations;
    private final AuditEventService audit;
    private final Clock clock;
    private final ScheduledNotificationRetryService scheduledRetries;

    public ChannelDeliveryManagement(EnterpriseMapper enterprises, NotificationChannelDeliveryMapper deliveries,
                                      ChannelDeliveryStore store, ChannelDeliveryPolicy policy, ChannelDeliveryAccess access,
                                      ChannelDeliveryQueryService queries, ChannelNotificationRouting routing, NotificationWriteService writer,
                                      IntegrationManagementPolicy integrationPolicy, IntegrationQueryService integrations,
                                      AuditEventService audit, Clock clock, ScheduledNotificationRetryService scheduledRetries) {
        this.enterprises = enterprises;
        this.deliveries = deliveries;
        this.store = store;
        this.policy = policy;
        this.access = access;
        this.queries = queries;
        this.routing = routing;
        this.writer = writer;
        this.integrationPolicy = integrationPolicy;
        this.integrations = integrations;
        this.audit = audit;
        this.clock = clock;
        this.scheduledRetries = scheduledRetries;
    }

    public void authorizeRetry(UserEntity actor, String enterprise, boolean system, String id) {
        enterprises.lockEnterprise(enterprise).filter("active"::equals).orElseThrow(ChannelDeliveryAccess::unavailable);
        var row = queries.require(enterprise, id);
        if (!access.retry(actor, queries.notification(enterprise, row.getNotificationId()), row.getDeliveryReason(), system)) {
            throw ChannelDeliveryAccess.unavailable();
        }
    }

    public ChannelDeliveryDetail retry(UserEntity actor, String enterprise, boolean system, String id,
                                       long revision, ChannelDeliveryRetryRequest input) {
        authorizeRetry(actor, enterprise, system, id);
        var original = queries.require(enterprise, id);
        scheduledRetries.reserve(queries.notification(enterprise, original.getNotificationId()));
        var row = deliveries.lock(enterprise, id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        if (!Set.of("failed", "unknown").contains(row.getStatus()) || row.getManualRetryCount() >= 3
            || row.getPayloadJson() == null || !row.getExpiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTIFICATION_RETRY_UNAVAILABLE", "此发送记录不能再次尝试，请查看结果或创建新的通知。");
        }
        if ("unknown".equals(row.getStatus()) && !Boolean.TRUE.equals(input.confirmMayDuplicate())) {
            throw ApiException.invalidField("confirmMayDuplicate", "此前请求可能已被平台接受，请确认能够接受重复通知后再试。");
        }
        var target = new ChannelDeliveryTarget(row.getConnectionId(), row.getCredentialRevision(), row.getBindingId(), row.getBindingRevision());
        var blocked = policy.check(queries.notification(enterprise, row.getNotificationId()), target, row.getDeliveryReason(),
            policy.connection(enterprise, row.getConnectionId()), policy.binding(enterprise, row.getConnectionId(), row.getRecipientUserId(), row.getBindingId()));
        if (blocked != null) {
            throw new ApiException(HttpStatus.CONFLICT, blocked.code(), blocked.summary());
        }
        row.setManualRetryCount(row.getManualRetryCount() + 1);
        row.setDispatchGeneration(row.getDispatchGeneration() + 1);
        row.setMaxAttempts(row.getAttemptCount() + 1);
        row.setStatus("pending");
        row.setLastErrorCode(null);
        row.setLastErrorSummary(null);
        routing.createJob(row);
        store.save(row);
        audit.record(enterprise, actor, "notification.delivery_retry", "notification_delivery", id, "人工再次尝试发送通知",
            Map.of("manualRetryCount", row.getManualRetryCount(), "confirmedPossibleDuplicate", Boolean.TRUE.equals(input.confirmMayDuplicate())));
        return queries.detail(actor, enterprise, system, id);
    }

    public ChannelDeliveryDetail test(UserEntity actor, String enterprise, boolean system, String connection, long revision,
                                      IntegrationTestMessageRequest input) {
        integrationPolicy.require(actor, enterprise, system, "integration.test", true);
        var app = integrations.require(enterprise, connection);
        if (app.getRevision() != revision) {
            throw ApiException.versionConflict(app.getRevision());
        }
        String recipient = ResourceInput.text(input.recipientUserId(), "recipientUserId", 100, true);
        var target = policy.capture(enterprise, recipient, app);
        if (target.bindingId() == null) {
            throw ApiException.invalidField("recipientUserId", "此成员尚未绑定所选应用，请先完成绑定。");
        }
        var notice = new Notice("channel-test:" + UUID.randomUUID(), "integration", "企业消息接入测试", "管理员正在验证此应用的通知发送，请确认能够收到本条测试消息。", null, null);
        var written = writer.write(enterprise, recipient, notice, null, actor.id())
            .orElseThrow(() -> ApiException.invalidField("recipientUserId", "请选择当前企业中的有效成员。"));
        var blocked = policy.check(written.notification(), target, "test", app, policy.binding(enterprise, connection, recipient, target.bindingId()));
        if (blocked != null) {
            throw new ApiException(HttpStatus.CONFLICT, blocked.code(), blocked.summary());
        }
        var delivery = routing.enqueue(written.notification(), target, "test", clock.instant().plusSeconds(3600));
        audit.record(enterprise, actor, "integration.test_message", "integration", connection, "排入企业接入测试通知",
            Map.of("recipientUserId", recipient, "deliveryId", delivery.getId()));
        return queries.detail(actor, enterprise, system, delivery.getId());
    }
}
