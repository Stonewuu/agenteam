package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.mapper.notification.NotificationRecipientMapper;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.notification.response.NotificationRecipientOption;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Service;

/** 超级管理员不必加入企业即可选择已授权的测试接收成员。 */
@Service
public class IntegrationRecipientService {
    private final IntegrationManagementPolicy policy;
    private final IntegrationQueryService integrations;
    private final NotificationRecipientMapper recipients;
    private final ListPagination pagination;

    public IntegrationRecipientService(IntegrationManagementPolicy policy, IntegrationQueryService integrations,
                                        NotificationRecipientMapper recipients, ListPagination pagination) {
        this.policy = policy;
        this.integrations = integrations;
        this.recipients = recipients;
        this.pagination = pagination;
    }

    public PageResponse<NotificationRecipientOption> list(UserEntity actor, String enterprise, boolean system, String connection,
                                                         String search, String cursor, Integer count) {
        policy.require(actor, enterprise, system, "integration.test", false);
        integrations.require(enterprise, connection);
        String text = search == null ? "" : ResourceInput.text(search, "search", 80, false);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.id(), enterprise, system ? "system/channel-recipients" : "channel-recipients",
            connection + ":" + text, "joined_desc");
        var position = pagination.read(cursor, binding);
        var page = pagination.page(recipients.page(enterprise, connection, null, text, position, limit), limit, binding,
            row -> new PagePosition(row.getJoinedAt(), row.getId()));
        return new PageResponse<>(page.items().stream().map(row -> new NotificationRecipientOption(row.getId(), row.getName())).toList(),
            page.nextCursor(), page.hasMore());
    }
}
