package com.stonewu.agenteam.service.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.schedule.entity.NotificationScheduleRecipient;
import com.stonewu.agenteam.model.schedule.response.ScheduleActionView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 计划页先确定当前页，再批量读取成员和渠道名称；不逐计划或逐成员重复查关联。 */
@Service
public class ScheduleRecipientViewService {
    private final IdentityQueryMapper members;
    private final EnterpriseIntegrationMapper connections;

    public ScheduleRecipientViewService(IdentityQueryMapper members, EnterpriseIntegrationMapper connections) {
        this.members = members;
        this.connections = connections;
    }

    public Map<String, List<ScheduleActionView.Recipient>> views(String enterprise, Map<String, List<NotificationScheduleRecipient>> targets) {
        var users = targets.values().stream().flatMap(List::stream).map(NotificationScheduleRecipient::userId).distinct().toList();
        if (users.isEmpty()) {
            return Map.of();
        }
        var names = members.selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
            .in(EnterpriseMemberRow::getUserId, users)).stream().collect(Collectors.toMap(EnterpriseMemberRow::getUserId, EnterpriseMemberRow::getDisplayName));
        var ids = targets.values().stream().flatMap(List::stream).flatMap(value -> value.connectionIds().stream()).distinct().toList();
        Map<String, EnterpriseIntegrationRow> applications = ids.isEmpty() ? Map.of() : connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).in(EnterpriseIntegrationRow::getId, ids)).stream()
            .collect(Collectors.toMap(EnterpriseIntegrationRow::getId, Function.identity()));
        return targets.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream().map(person ->
            new ScheduleActionView.Recipient(person.userId(), names.getOrDefault(person.userId(), "成员已不可用"), person.connectionIds().stream().map(id -> {
                var app = applications.get(id);
                if (app == null) {
                    throw new IllegalStateException("已保存计划的接收渠道不存在");
                }
                return new ScheduleActionView.Channel(app.getId(), app.getName(), app.getProviderCode());
            }).toList())).toList()));
    }
}
