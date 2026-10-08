package com.stonewu.agenteam.service.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.notification.NotificationRecipientMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduledNotificationTargetMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleRecipient;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleSnapshot;
import com.stonewu.agenteam.model.schedule.entity.ScheduledNotificationTargetRow;
import com.stonewu.agenteam.model.schedule.request.NotificationScheduleActionRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 保存时严格验证接收人；触发快照保留失效目标，由本次执行逐人记录原因而不中断其他接收人。 */
@Service
public class ScheduleNotificationTargetService {
    private final ScheduledNotificationTargetMapper targets;
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;
    private final NotificationRecipientMapper members;
    private final PermissionMapper permissions;
    private final IntegrationProviderRegistry providers;
    private final Clock clock;

    public ScheduleNotificationTargetService(ScheduledNotificationTargetMapper targets, EnterpriseIntegrationMapper connections,
                                             UserChannelBindingMapper bindings, NotificationRecipientMapper members,
                                             PermissionMapper permissions, IntegrationProviderRegistry providers, Clock clock) {
        this.targets = targets;
        this.connections = connections;
        this.bindings = bindings;
        this.members = members;
        this.permissions = permissions;
        this.providers = providers;
        this.clock = clock;
    }

    public List<NotificationScheduleRecipient> validate(AuthContext actor, List<NotificationScheduleActionRequest.Recipient> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 50) {
            throw ApiException.invalidField("action.config.recipients", "请选择一至五十名明确接收成员。");
        }
        var users = requested.stream().map(NotificationScheduleActionRequest.Recipient::userId).toList();
        requireScope(actor, users);
        if (new HashSet<>(users).size() != users.size()) {
            throw ApiException.invalidField("action.config.recipients", "同一成员不能重复选择。");
        }
        var active = members.selected(actor.enterpriseId(), users).stream().map(value -> value.getId()).collect(Collectors.toSet());
        var ids = requested.stream().flatMap(value -> value.connectionIds().stream()).distinct().toList();
        var apps = applications(actor.enterpriseId(), ids);
        var bound = currentBindings(actor.enterpriseId(), users, ids);
        var result = new ArrayList<NotificationScheduleRecipient>();
        for (int index = 0; index < requested.size(); index++) {
            var person = requested.get(index);
            String field = "action.config.recipients." + index;
            if (!active.contains(person.userId())) {
                throw ApiException.invalidField(field + ".userId", "此接收成员已不可用，请重新选择。");
            }
            var selectedProviders = new HashSet<String>();
            for (String id : person.connectionIds()) {
                var app = apps.get(id);
                if (app == null || !"enabled".equals(app.getStatus()) || !Boolean.TRUE.equals(app.getMessagingEnabled())) {
                    throw ApiException.invalidField(field + ".connectionIds", "所选渠道当前不允许发送通知，请重新选择。");
                }
                providers.sender(app.getProviderCode());
                if (!selectedProviders.add(app.getProviderCode())) {
                    throw ApiException.invalidField(field + ".connectionIds", "每名成员在同一平台只能选择一个接入应用。");
                }
                var binding = bound.get(new BindingKey(person.userId(), id));
                if (binding == null || !Boolean.TRUE.equals(binding.getReceiveEnabled())) {
                    throw ApiException.invalidField(field + ".connectionIds", "此成员尚未绑定所选应用，或已经关闭通知接收。");
                }
            }
            result.add(new NotificationScheduleRecipient(person.userId(), List.copyOf(person.connectionIds())));
        }
        return List.copyOf(result);
    }

    public void requireScope(AuthContext actor, List<String> users) {
        if (!maySend(actor, users)) {
            throw ApiException.invalidField("action.config.recipients", "当前只能向自己发送通知，向其他成员发送需要企业授权。");
        }
    }

    public void requireExecutionScope(AuthContext actor, List<String> users) {
        if (!maySend(actor, users)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCHEDULE_PERMISSION_DENIED", "创建人已无权向所选成员发送通知。");
        }
    }

    private boolean maySend(AuthContext actor, List<String> users) {
        return users.stream().allMatch(actor.userId()::equals)
            || permissions.operationScope(actor.userId(), actor.enterpriseId(), "notification.send.enterprise").filter(DataScope.ENTERPRISE::equals).isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(String enterprise, String schedule, List<NotificationScheduleRecipient> recipients) {
        targets.delete(new LambdaQueryWrapper<ScheduledNotificationTargetRow>().eq(ScheduledNotificationTargetRow::getEnterpriseId, enterprise)
            .eq(ScheduledNotificationTargetRow::getScheduleId, schedule));
        var rows = new ArrayList<ScheduledNotificationTargetRow>();
        for (var recipient : recipients) {
            rows.add(row(enterprise, schedule, recipient.userId(), null));
            for (String connection : recipient.connectionIds()) {
                rows.add(row(enterprise, schedule, recipient.userId(), connection));
            }
        }
        if (!rows.isEmpty()) {
            targets.insert(rows, 100);
        }
    }

    public List<NotificationScheduleRecipient> list(String enterprise, String schedule) {
        return forSchedules(enterprise, List.of(schedule)).getOrDefault(schedule, List.of());
    }

    public Map<String, List<NotificationScheduleRecipient>> forSchedules(String enterprise, List<String> schedules) {
        if (schedules.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
        var rows = targets.selectList(new LambdaQueryWrapper<ScheduledNotificationTargetRow>()
            .eq(ScheduledNotificationTargetRow::getEnterpriseId, enterprise).in(ScheduledNotificationTargetRow::getScheduleId, schedules)
            .orderByAsc(ScheduledNotificationTargetRow::getScheduleId, ScheduledNotificationTargetRow::getRecipientUserId, ScheduledNotificationTargetRow::getChannelKey));
        for (var row : rows) {
            var channels = grouped.computeIfAbsent(row.getScheduleId(), ignored -> new TreeMap<>())
                .computeIfAbsent(row.getRecipientUserId(), ignored -> new ArrayList<>());
            if (row.getConnectionId() != null) {
                channels.add(row.getConnectionId());
            }
        }
        return grouped.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().entrySet().stream()
            .map(person -> new NotificationScheduleRecipient(person.getKey(), List.copyOf(person.getValue()))).toList()));
    }

    public List<NotificationScheduleSnapshot.Recipient> capture(String enterprise, List<NotificationScheduleRecipient> recipients) {
        var ids = recipients.stream().flatMap(value -> value.connectionIds().stream()).distinct().toList();
        var apps = applications(enterprise, ids);
        var bound = currentBindings(enterprise, recipients.stream().map(NotificationScheduleRecipient::userId).toList(), ids);
        return recipients.stream().map(person -> new NotificationScheduleSnapshot.Recipient(person.userId(), person.connectionIds().stream().map(id -> {
            var app = apps.get(id);
            if (app == null) {
                throw new IllegalStateException("计划接收渠道缺少企业接入记录");
            }
            var binding = bound.get(new BindingKey(person.userId(), id));
            return new ChannelDeliveryTarget(id, app.getCredentialRevision(), binding == null ? null : binding.getId(), binding == null ? null : binding.getRevision());
        }).toList())).toList();
    }

    private Map<String, EnterpriseIntegrationRow> applications(String enterprise, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).in(EnterpriseIntegrationRow::getId, ids)).stream()
            .collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
    }

    private Map<BindingKey, UserChannelBindingRow> currentBindings(String enterprise, List<String> users, List<String> ids) {
        if (ids.isEmpty() || users.isEmpty()) {
            return Map.of();
        }
        return bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)
            .in(UserChannelBindingRow::getUserId, users).in(UserChannelBindingRow::getConnectionId, ids).eq(UserChannelBindingRow::getStatus, "active"))
            .stream().collect(Collectors.toMap(value -> new BindingKey(value.getUserId(), value.getConnectionId()), Function.identity()));
    }

    private ScheduledNotificationTargetRow row(String enterprise, String schedule, String user, String connection) {
        var row = new ScheduledNotificationTargetRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setScheduleId(schedule);
        row.setRecipientUserId(user);
        row.setConnectionId(connection);
        row.setCreatedAt(clock.instant());
        return row;
    }

    private record BindingKey(String user, String connection) {
    }
}
