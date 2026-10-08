package com.stonewu.agenteam.service.schedule.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleSnapshot;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.response.ScheduleRecipientResult;
import com.stonewu.agenteam.service.notification.ChannelDeliveryQueryService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 批量读取本次接收人和发送状态，保存后的计划接收人列表不参与历史详情。 */
@Service
public class NotificationScheduleDetailService {
    private final NotificationSqlMapper notices;
    private final NotificationChannelDeliveryMapper deliveries;
    private final ChannelDeliveryQueryService deliveryQueries;
    private final IdentityQueryMapper members;
    private final ResourceJson json;
    private final ObjectMapper objects;

    public NotificationScheduleDetailService(NotificationSqlMapper notices, NotificationChannelDeliveryMapper deliveries,
                                               ChannelDeliveryQueryService deliveryQueries, IdentityQueryMapper members, ResourceJson json, ObjectMapper objects) {
        this.notices = notices;
        this.deliveries = deliveries;
        this.deliveryQueries = deliveryQueries;
        this.members = members;
        this.json = json;
        this.objects = objects;
    }

    public List<ScheduleRecipientResult> details(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        if (occurrence.getActionSnapshotJson() == null) {
            return List.of();
        }
        var snapshot = objects.convertValue(json.read(occurrence.getActionSnapshotJson()), NotificationScheduleSnapshot.class);
        var recipients = snapshot.recipients().stream().map(NotificationScheduleSnapshot.Recipient::userId).toList();
        var names = members.selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, actor.enterpriseId())
            .in(EnterpriseMemberRow::getUserId, recipients)).stream().collect(Collectors.toMap(EnterpriseMemberRow::getUserId, EnterpriseMemberRow::getDisplayName));
        var written = notices.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, actor.enterpriseId())
            .eq(NotificationRow::getSourceOccurrenceId, occurrence.getId()));
        var byUser = written.stream().collect(Collectors.toMap(NotificationRow::getUserId, Function.identity()));
        var ids = written.stream().map(NotificationRow::getId).toList();
        var sending = ids.isEmpty() ? List.<NotificationChannelDeliveryRow>of() : deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, actor.enterpriseId()).in(NotificationChannelDeliveryRow::getNotificationId, ids)
            .orderByAsc(NotificationChannelDeliveryRow::getCreatedAt, NotificationChannelDeliveryRow::getId));
        var channels = deliveryQueries.views(actor.user(), actor.enterpriseId(), false, sending).stream().collect(Collectors.groupingBy(value -> value.notificationId()));
        var result = json.read(occurrence.getActionResultJson());
        var blocked = new HashMap<String, String>();
        for (var item : result.path("blockedRecipients")) {
            blocked.put(item.path("userId").asText(), item.path("reason").asText());
        }
        return recipients.stream().map(user -> {
            var notice = byUser.get(user);
            String status = notice != null ? "delivered" : blocked.containsKey(user) ? "blocked"
                : "cancelled".equals(occurrence.getStatus()) ? "cancelled" : result.path("prepared").asBoolean(false) ? "blocked"
                : "queued".equals(occurrence.getStatus()) ? "pending" : occurrence.getStatus();
            return new ScheduleRecipientResult(user, names.getOrDefault(user, "已不可用成员"), status, blocked.get(user),
                notice == null ? List.of() : channels.getOrDefault(notice.getId(), List.of()));
        }).toList();
    }
}
