package com.stonewu.agenteam.service.schedule.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleSnapshot;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.service.notification.ChannelDeliveryStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按原接收人和原渠道逐项统计，不能只用已经创建成功的发送记录作为分母。 */
@Service
public class NotificationScheduleResultService {
    private final NotificationSqlMapper notifications;
    private final NotificationChannelDeliveryMapper deliveries;
    private final ResourceJson json;
    private final ObjectMapper objects;

    public NotificationScheduleResultService(NotificationSqlMapper notifications, NotificationChannelDeliveryMapper deliveries,
                                               ResourceJson json, ObjectMapper objects) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.json = json;
        this.objects = objects;
    }

    public ScheduleActionSubmission summarize(ScheduledOccurrenceRow row) {
        var snapshot = objects.convertValue(json.read(row.getActionSnapshotJson()), NotificationScheduleSnapshot.class);
        var result = (ObjectNode) json.read(row.getActionResultJson()).deepCopy();
        var notices = notifications.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, row.getEnterpriseId())
            .eq(NotificationRow::getSourceOccurrenceId, row.getId()));
        var ids = notices.stream().map(NotificationRow::getId).toList();
        List<NotificationChannelDeliveryRow> sends = ids.isEmpty() ? List.of() : deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, row.getEnterpriseId()).in(NotificationChannelDeliveryRow::getNotificationId, ids));
        var people = notices.stream().map(NotificationRow::getUserId).collect(Collectors.toSet());
        var channels = sends.stream().collect(Collectors.toMap(value -> new Target(value.getRecipientUserId(), value.getConnectionId()), Function.identity()));
        int total = snapshot.recipients().stream().mapToInt(person -> 1 + person.channels().size()).sum();
        int inApp = 0;
        int accepted = 0;
        int blocked = 0;
        int failed = 0;
        int cancelled = 0;
        int unknown = 0;
        int pending = 0;
        for (var person : snapshot.recipients()) {
            if (people.contains(person.userId())) {
                inApp++;
            } else {
                blocked++;
            }
            for (var channel : person.channels()) {
                var delivery = channels.get(new Target(person.userId(), channel.connectionId()));
                if (delivery == null || "blocked".equals(delivery.getStatus())) {
                    blocked++;
                } else if (ChannelDeliveryStore.ACTIVE.contains(delivery.getStatus())) {
                    pending++;
                } else if ("accepted".equals(delivery.getStatus())) {
                    accepted++;
                } else if ("cancelled".equals(delivery.getStatus())) {
                    cancelled++;
                } else if ("unknown".equals(delivery.getStatus())) {
                    unknown++;
                } else {
                    failed++;
                }
            }
        }
        result.put("totalCount", total).put("inAppCount", inApp).put("acceptedCount", accepted).put("blockedCount", blocked)
            .put("failedCount", failed).put("cancelledCount", cancelled).put("unknownCount", unknown).put("pendingCount", pending);
        int successful = inApp + accepted;
        String status = pending > 0 ? "running" : unknown > 0 ? "unknown" : successful == total ? "completed"
            : successful > 0 ? "partially_failed" : failed > 0 ? "failed" : cancelled > 0 ? "cancelled" : "blocked";
        String reason = Map.of("partially_failed", "SCHEDULE_NOTIFICATION_PARTIAL", "unknown", "SCHEDULE_NOTIFICATION_UNKNOWN",
            "failed", "SCHEDULE_NOTIFICATION_FAILED", "blocked", "SCHEDULE_NOTIFICATION_BLOCKED", "cancelled", "SCHEDULE_CANCELLED").get(status);
        return new ScheduleActionSubmission(status, reason, null, null, result);
    }

    private record Target(String user, String connection) {
    }
}
