package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelPreferenceMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationRecipientMapper;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.notification.entity.ScheduledChannelNotification;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 站内通知、逐渠道结果和后台工作在同一事务保存，提交之后才允许调用平台。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelNotificationRouting {
    private final NotificationChannelDeliveryMapper deliveries;
    private final UserChannelPreferenceMapper preferences;
    private final ChannelDeliveryPolicy policy;
    private final IntegrationProviderRegistry providers;
    private final IntegrationQueryService queries;
    private final IntegrationPublicUrls urls;
    private final ChannelPayloadCodec payloads;
    private final BackgroundJobMapper jobs;
    private final NotificationCategoryCatalog categories;
    private final ObjectMapper json;
    private final Clock clock;
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;
    private final NotificationRecipientMapper members;

    public ChannelNotificationRouting(NotificationChannelDeliveryMapper deliveries, UserChannelPreferenceMapper preferences,
                                      ChannelDeliveryPolicy policy, IntegrationProviderRegistry providers,
                                      IntegrationQueryService queries, IntegrationPublicUrls urls, ChannelPayloadCodec payloads,
                                      BackgroundJobMapper jobs, NotificationCategoryCatalog categories, ObjectMapper json, Clock clock,
                                      EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings, NotificationRecipientMapper members) {
        this.deliveries = deliveries;
        this.preferences = preferences;
        this.policy = policy;
        this.providers = providers;
        this.queries = queries;
        this.urls = urls;
        this.payloads = payloads;
        this.jobs = jobs;
        this.categories = categories;
        this.json = json;
        this.clock = clock;
        this.connections = connections;
        this.bindings = bindings;
        this.members = members;
    }

    public void automatic(NotificationRow notice, Instant eventCreatedAt) {
        if (!policy.automaticAllowed(notice)) {
            return;
        }
        Instant expires = eventCreatedAt.plus(categories.lifetime(notice.getCategory()));
        if (!expires.isAfter(clock.instant())) {
            return;
        }
        var selected = preferences.selectList(new LambdaQueryWrapper<UserChannelPreferenceRow>()
            .eq(UserChannelPreferenceRow::getEnterpriseId, notice.getEnterpriseId())
            .eq(UserChannelPreferenceRow::getUserId, notice.getUserId()).eq(UserChannelPreferenceRow::getCategory, notice.getCategory())
            .eq(UserChannelPreferenceRow::getEnabled, true).orderByAsc(UserChannelPreferenceRow::getConnectionId));
        if (selected.isEmpty()) {
            return;
        }
        var ids = selected.stream().map(UserChannelPreferenceRow::getConnectionId).toList();
        var applications = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, notice.getEnterpriseId()).in(EnterpriseIntegrationRow::getId, ids)
            .ne(EnterpriseIntegrationRow::getStatus, "deleted").orderByAsc(EnterpriseIntegrationRow::getId));
        var bound = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, notice.getEnterpriseId()).eq(UserChannelBindingRow::getUserId, notice.getUserId())
            .in(UserChannelBindingRow::getConnectionId, ids).eq(UserChannelBindingRow::getStatus, "active"))
            .stream().collect(Collectors.toMap(UserChannelBindingRow::getConnectionId, Function.identity()));
        var existing = deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .select(NotificationChannelDeliveryRow::getConnectionId).eq(NotificationChannelDeliveryRow::getEnterpriseId, notice.getEnterpriseId())
            .eq(NotificationChannelDeliveryRow::getNotificationId, notice.getId())).stream()
            .map(NotificationChannelDeliveryRow::getConnectionId).collect(Collectors.toSet());
        for (var connection : applications) {
            if (!existing.contains(connection.getId())) {
                var binding = bound.get(connection.getId());
                var target = new ChannelDeliveryTarget(connection.getId(), connection.getCredentialRevision(), binding == null ? null : binding.getId(),
                    binding == null ? null : binding.getRevision());
                persist(notice, target, "automatic", expires, connection, binding, policy.checkTarget(target, connection, binding));
            }
        }
    }

    public NotificationChannelDeliveryRow enqueue(NotificationRow notice, ChannelDeliveryTarget target, String reason, Instant expires) {
        var existing = deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, notice.getEnterpriseId())
            .eq(NotificationChannelDeliveryRow::getNotificationId, notice.getId())
            .eq(NotificationChannelDeliveryRow::getConnectionId, target.connectionId()));
        if (existing != null) {
            return existing;
        }
        var connection = policy.connection(notice.getEnterpriseId(), target.connectionId());
        if (connection == null) {
            throw new IllegalStateException("通知所选的企业接入不存在");
        }
        var binding = policy.binding(notice.getEnterpriseId(), target.connectionId(), notice.getUserId(), target.bindingId());
        var blocked = policy.check(notice, target, reason, connection, binding);
        return persist(notice, target, reason, expires, connection, binding, blocked);
    }

    public List<NotificationChannelDeliveryRow> scheduled(List<ScheduledChannelNotification> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        String enterprise = requests.getFirst().notification().getEnterpriseId();
        if (requests.stream().anyMatch(request -> !enterprise.equals(request.notification().getEnterpriseId()))) {
            throw new IllegalArgumentException("同一批定时通知必须属于同一企业");
        }
        var users = requests.stream().map(request -> request.notification().getUserId()).distinct().toList();
        var active = members.selected(enterprise, users).stream().map(value -> value.getId()).collect(Collectors.toSet());
        var applications = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .in(EnterpriseIntegrationRow::getId, requests.stream().map(request -> request.target().connectionId()).distinct().toList()))
            .stream().collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        var bindingIds = requests.stream().map(request -> request.target().bindingId()).filter(value -> value != null).distinct().toList();
        Map<String, UserChannelBindingRow> bound = bindingIds.isEmpty() ? Map.of() : bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).in(UserChannelBindingRow::getId, bindingIds)).stream()
            .collect(Collectors.toMap(UserChannelBindingRow::getId, Function.identity()));
        var previous = deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).in(NotificationChannelDeliveryRow::getNotificationId,
                requests.stream().map(request -> request.notification().getId()).distinct().toList())).stream()
            .collect(Collectors.toMap(value -> new DeliveryKey(value.getNotificationId(), value.getConnectionId()), Function.identity()));
        Map<SenderKey, Boolean> senders = new HashMap<>();
        var result = new ArrayList<NotificationChannelDeliveryRow>();
        for (var request : requests) {
            var notice = request.notification();
            var target = request.target();
            var key = new DeliveryKey(notice.getId(), target.connectionId());
            if (previous.containsKey(key)) {
                result.add(previous.get(key));
                continue;
            }
            var app = applications.get(target.connectionId());
            if (app == null) {
                throw new IllegalStateException("定时通知的接入记录已丢失");
            }
            var binding = target.bindingId() == null ? null : bound.get(target.bindingId());
            if (binding != null && (!notice.getUserId().equals(binding.getUserId()) || !target.connectionId().equals(binding.getConnectionId()))) {
                binding = null;
            }
            var blocked = policy.checkTarget(target, app, binding);
            boolean sender = senders.computeIfAbsent(new SenderKey(notice.getInitiatorUserId(), notice.getUserId().equals(notice.getInitiatorUserId())),
                ignored -> policy.senderAllowed(enterprise, notice.getInitiatorUserId(), notice.getUserId(), "scheduled"));
            if (!active.contains(notice.getUserId())) {
                blocked = new ChannelDeliveryPolicy.Block("blocked", "RECIPIENT_UNAVAILABLE", "接收人已不在企业中或账号已停用。");
            } else if (!sender) {
                blocked = new ChannelDeliveryPolicy.Block("blocked", "NOTIFICATION_SENDER_DENIED", "发起人已无权向此成员发送通知。");
            }
            var saved = persist(notice, target, "scheduled", request.expiresAt(), app, binding, blocked);
            previous.put(key, saved);
            result.add(saved);
        }
        return List.copyOf(result);
    }

    private NotificationChannelDeliveryRow persist(NotificationRow notice, ChannelDeliveryTarget target, String reason, Instant expires,
                                                    EnterpriseIntegrationRow connection, UserChannelBindingRow binding, ChannelDeliveryPolicy.Block blocked) {
        if (!expires.isAfter(clock.instant())) {
            blocked = new ChannelDeliveryPolicy.Block("expired", "NOTIFICATION_EXPIRED", "此通知已超过发送有效期。");
        }
        var row = new NotificationChannelDeliveryRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(notice.getEnterpriseId());
        row.setNotificationId(notice.getId());
        row.setRecipientUserId(notice.getUserId());
        row.setConnectionId(target.connectionId());
        row.setCredentialRevision(target.credentialRevision());
        row.setBindingId(target.bindingId());
        row.setBindingRevision(target.bindingRevision());
        row.setDeliveryReason(reason);
        row.setProviderRequestId(UUID.randomUUID().toString());
        row.setStatus(blocked == null ? "pending" : blocked.status());
        row.setAttemptCount(0);
        row.setMaxAttempts(5);
        row.setManualRetryCount(0);
        row.setDispatchGeneration(1);
        row.setRevision(1L);
        row.setExpiresAt(expires);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        if (blocked == null) {
            var payload = providers.sender(connection.getProviderCode()).render(queries.application(connection), binding.getExternalSubjectId(),
                notice.getTitle(), notice.getBody(), urls.notification(notice.getEnterpriseId(), notice.getId()), row.getProviderRequestId());
            row.setPayloadJson(payloads.encode(payload));
            row.setPayloadHash(payloads.hash(payload));
            createJob(row);
        } else {
            row.setLastErrorCode(blocked.code());
            row.setLastErrorSummary(blocked.summary());
        }
        deliveries.insert(row);
        return row;
    }

    public void createJob(NotificationChannelDeliveryRow row) {
        String job = UUID.randomUUID().toString();
        jobs.enqueue(job, row.getEnterpriseId(), row.getRecipientUserId(), "channel_delivery",
            "channel-delivery:" + row.getId() + ":" + row.getDispatchGeneration(),
            payloads.encode(json.valueToTree(Map.of("deliveryId", row.getId(), "generation", row.getDispatchGeneration()))), clock.instant());
        jobs.setAttemptLimit(job, 1440);
        row.setActiveJobId(job);
        row.setNextAttemptAt(clock.instant());
    }

    private record DeliveryKey(String notice, String connection) {
    }

    private record SenderKey(String user, boolean self) {
    }
}
