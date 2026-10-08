package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.notification.NotificationRecipientMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.schedule.entity.ScheduleChannelOptionRow;
import com.stonewu.agenteam.model.schedule.response.ScheduleActionOption;
import com.stonewu.agenteam.model.schedule.response.ScheduleRecipientOption;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** 接收人选择使用通知发送范围，不借用组织管理的成员查看权限。 */
@Service
@Transactional(readOnly = true)
public class ScheduleSelectionService {
    private final ScheduleActionRegistry actions;
    private final NotificationRecipientMapper members;
    private final ScheduleOptionMapper options;
    private final PermissionMapper permissions;
    private final EnterpriseAuthorizationService authorization;
    private final IntegrationProviderRegistry providers;
    private final ListPagination pagination;

    public ScheduleSelectionService(ScheduleActionRegistry actions, NotificationRecipientMapper members, ScheduleOptionMapper options,
                                      PermissionMapper permissions, EnterpriseAuthorizationService authorization,
                                      IntegrationProviderRegistry providers, ListPagination pagination) {
        this.actions = actions;
        this.members = members;
        this.options = options;
        this.permissions = permissions;
        this.authorization = authorization;
        this.providers = providers;
        this.pagination = pagination;
    }

    public List<ScheduleActionOption> actions(AuthContext actor) {
        authorization.require(actor, "schedule.manage");
        return actions.available(actor).stream().map(handler -> new ScheduleActionOption(handler.type(), handler.name(),
            handler.schemaVersions().stream().sorted().toList(), handler.usesAgent(), handler.configurationSchema())).toList();
    }

    public PageResponse<ScheduleRecipientOption> recipients(AuthContext actor, String query, String cursor, Integer count) {
        authorization.require(actor, "schedule.manage");
        boolean enterprise = permissions.operationScope(actor.userId(), actor.enterpriseId(), "notification.send.enterprise")
            .filter(DataScope.ENTERPRISE::equals).isPresent();
        int limit = pagination.limit(count);
        String search = pagination.query(query);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "schedule-recipients", enterprise + ":" + search, "joined_desc");
        var rows = members.page(actor.enterpriseId(), null, enterprise ? null : actor.userId(), search, pagination.read(cursor, binding), limit);
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.getJoinedAt(), row.getId()));
        var channels = options.channels(actor.enterpriseId(), page.items().stream().map(row -> row.getId()).toList()).stream()
            .collect(Collectors.groupingBy(ScheduleChannelOptionRow::getUserId));
        return new PageResponse<>(page.items().stream().map(row -> new ScheduleRecipientOption(row.getId(), row.getName(),
            channels.getOrDefault(row.getId(), List.of()).stream().map(channel -> new ScheduleRecipientOption.Channel(channel.getConnectionId(),
                channel.getName(), channel.getProviderCode(), providers.require(channel.getProviderCode()).name())).toList())).toList(), page.nextCursor(), page.hasMore());
    }
}
