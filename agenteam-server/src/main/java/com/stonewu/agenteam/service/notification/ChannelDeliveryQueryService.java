package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelAttemptMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelAttemptRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryDetail;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 分页先取主记录，再批量读取接入、成员和通知来源，不通过运维页面返回通知正文。 */
@Service
public class ChannelDeliveryQueryService {
    private final NotificationChannelDeliveryMapper deliveries;
    private final NotificationChannelAttemptMapper attempts;
    private final NotificationSqlMapper notifications;
    private final EnterpriseIntegrationMapper connections;
    private final IdentityQueryMapper members;
    private final IntegrationProviderRegistry providers;
    private final ChannelDeliveryAccess access;
    private final ListPagination pagination;
    private final Clock clock;

    public ChannelDeliveryQueryService(NotificationChannelDeliveryMapper deliveries, NotificationChannelAttemptMapper attempts,
                                        NotificationSqlMapper notifications, EnterpriseIntegrationMapper connections, IdentityQueryMapper members,
                                        IntegrationProviderRegistry providers, ChannelDeliveryAccess access, ListPagination pagination, Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.notifications = notifications;
        this.connections = connections;
        this.members = members;
        this.providers = providers;
        this.access = access;
        this.pagination = pagination;
        this.clock = clock;
    }

    public PageResponse<ChannelDeliveryView> forConnection(UserEntity actor, String enterprise, boolean system, String connection,
                                                          String cursor, Integer count) {
        access.requireOperator(actor, enterprise, system, "notification.delivery.view");
        return page(actor, enterprise, system, connection, null, cursor, count);
    }

    public PageResponse<ChannelDeliveryView> forNotification(UserEntity actor, String enterprise, String notification, String cursor, Integer count) {
        var notice = notification(enterprise, notification);
        access.requireView(actor, notice, notice.getInitiatorUserId() == null ? "automatic" : "scheduled", false);
        return page(actor, enterprise, false, null, notification, cursor, count);
    }

    public ChannelDeliveryDetail detail(UserEntity actor, String enterprise, boolean system, String id) {
        var row = require(enterprise, id);
        access.requireView(actor, notification(enterprise, row.getNotificationId()), row.getDeliveryReason(), system);
        var history = attempts.selectList(new LambdaQueryWrapper<NotificationChannelAttemptRow>()
            .eq(NotificationChannelAttemptRow::getEnterpriseId, enterprise).eq(NotificationChannelAttemptRow::getDeliveryId, id)
            .orderByAsc(NotificationChannelAttemptRow::getAttemptNo)).stream().map(value -> new ChannelDeliveryDetail.Attempt(value.getAttemptNo(),
                value.getOutcome(), value.getHttpStatus(), value.getProviderCode(), value.getErrorSummary(), time(value.getStartedAt()), time(value.getFinishedAt()))).toList();
        return new ChannelDeliveryDetail(views(actor, enterprise, system, List.of(row)).getFirst(), history);
    }

    public NotificationChannelDeliveryRow require(String enterprise, String id) {
        var row = deliveries.selectOne(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).eq(NotificationChannelDeliveryRow::getId, id));
        if (row == null) {
            throw ChannelDeliveryAccess.unavailable();
        }
        return row;
    }

    public NotificationRow notification(String enterprise, String id) {
        var row = notifications.selectOne(new LambdaQueryWrapper<NotificationRow>()
            .eq(NotificationRow::getEnterpriseId, enterprise).eq(NotificationRow::getId, id));
        if (row == null) {
            throw ChannelDeliveryAccess.unavailable();
        }
        return row;
    }

    private PageResponse<ChannelDeliveryView> page(UserEntity actor, String enterprise, boolean system, String connection,
                                                  String notification, String cursor, Integer count) {
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.id(), enterprise, system ? "system/channel-deliveries" : "channel-deliveries",
            (connection == null ? "" : connection) + ":" + (notification == null ? "" : notification), "created_desc");
        var position = pagination.read(cursor, binding);
        var query = new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)
            .eq(connection != null, NotificationChannelDeliveryRow::getConnectionId, connection)
            .eq(notification != null, NotificationChannelDeliveryRow::getNotificationId, notification)
            .orderByDesc(NotificationChannelDeliveryRow::getCreatedAt, NotificationChannelDeliveryRow::getId);
        if (position != null) {
            query.and(group -> group.lt(NotificationChannelDeliveryRow::getCreatedAt, position.time()).or(equal -> equal
                .eq(NotificationChannelDeliveryRow::getCreatedAt, position.time()).lt(NotificationChannelDeliveryRow::getId, position.id())));
        }
        var rows = deliveries.selectPage(new Page<NotificationChannelDeliveryRow>(1, limit + 1, false), query).getRecords();
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        return new PageResponse<>(views(actor, enterprise, system, page.items()), page.nextCursor(), page.hasMore());
    }

    public List<ChannelDeliveryView> views(UserEntity actor, String enterprise, boolean system, List<NotificationChannelDeliveryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var applications = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).in(EnterpriseIntegrationRow::getId, rows.stream().map(NotificationChannelDeliveryRow::getConnectionId).distinct().toList()))
            .stream().collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        var recipients = members.selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
            .in(EnterpriseMemberRow::getUserId, rows.stream().map(NotificationChannelDeliveryRow::getRecipientUserId).distinct().toList()))
            .stream().collect(Collectors.toMap(EnterpriseMemberRow::getUserId, EnterpriseMemberRow::getDisplayName));
        var notices = notifications.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)
            .in(NotificationRow::getId, rows.stream().map(NotificationChannelDeliveryRow::getNotificationId).distinct().toList()))
            .stream().collect(Collectors.toMap(NotificationRow::getId, Function.identity()));
        boolean operator = access.operator(actor, enterprise, system, "notification.delivery.retry");
        Map<String, Boolean> senderPermissions = new HashMap<>();
        return rows.stream().map(row -> {
            var connection = applications.get(row.getConnectionId());
            boolean sender = actor.id().equals(notices.get(row.getNotificationId()).getInitiatorUserId())
                && senderPermissions.computeIfAbsent(row.getDeliveryReason() + ":" + actor.id().equals(row.getRecipientUserId()),
                    ignored -> access.originalSender(actor, notices.get(row.getNotificationId()), row.getDeliveryReason()));
            boolean retry = (operator || sender) && Set.of("failed", "unknown").contains(row.getStatus())
                && row.getManualRetryCount() < 3 && row.getPayloadJson() != null && row.getExpiresAt().isAfter(clock.instant());
            return new ChannelDeliveryView(row.getId(), row.getNotificationId(), recipients.get(row.getRecipientUserId()), connection.getId(),
                connection.getName(), providers.require(connection.getProviderCode()).name(), row.getStatus(), row.getAttemptCount(), row.getManualRetryCount(),
                time(row.getNextAttemptAt()), time(row.getExpiresAt()), time(row.getAcceptedAt()), row.getLastErrorSummary(), retry,
                "unknown".equals(row.getStatus()), Long.toString(row.getRevision()), time(row.getCreatedAt()));
        }).toList();
    }

    private String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
