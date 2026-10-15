package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.chat.entity.ChatTestMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import java.util.List;

/** 管理员选择已经自行授权聊天的测试成员，不代替成员建立账号绑定。 */
@Service
public class ChatTestMemberService {
    public record Choice(String bindingId, String name, String username) {
    }
    private final UserChannelBindingMapper bindings;
    private final ChatBotBindingService bots;
    private final ListPagination pagination;
    private final ChatIdentityService identities;

    public ChatTestMemberService(UserChannelBindingMapper bindings, ChatBotBindingService bots, ListPagination pagination, ChatIdentityService identities) {
        this.bindings = bindings;
        this.bots = bots;
        this.pagination = pagination;
        this.identities = identities;
    }

    public PageResponse<Choice> list(String actor, String enterprise, String botId, String text, String cursor, Integer count) {
        var bot = bots.require(enterprise, botId);
        String search = pagination.query(text);
        int limit = pagination.limit(count);
        var scope = new ListPagination.Binding(actor, enterprise, "chat/test-members", botId + ":" + search, "created_desc");
        var after = pagination.read(cursor, scope);
        var connections = identities.availableConnections(enterprise, bot.getConnectionId());
        var query = JoinWrappers.lambda(UserChannelBindingRow.class)
            .selectAs(UserChannelBindingRow::getId, ChatTestMemberRow::getId)
            .selectAs(UserChannelBindingRow::getCreatedAt, ChatTestMemberRow::getCreatedAt)
            .selectAs(EnterpriseMemberRow::getDisplayName, ChatTestMemberRow::getName)
            .selectAs(AppUserRow::getUsername, ChatTestMemberRow::getUsername)
            .innerJoin(EnterpriseMemberRow.class, on -> on.eq(EnterpriseMemberRow::getEnterpriseId, UserChannelBindingRow::getEnterpriseId)
                .eq(EnterpriseMemberRow::getUserId, UserChannelBindingRow::getUserId))
            .innerJoin(AppUserRow.class, AppUserRow::getId, UserChannelBindingRow::getUserId)
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise)
            .in(UserChannelBindingRow::getConnectionId, connections.isEmpty() ? List.of("") : connections)
            .and(value -> value.eq(UserChannelBindingRow::getConnectionId, bot.getConnectionId()).or()
                .isNotNull(UserChannelBindingRow::getExternalUnionId).ne(UserChannelBindingRow::getExternalUnionId, ""))
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getChatEnabled, true)
            .eq(EnterpriseMemberRow::getStatus, "active").eq(AppUserRow::getStatus, "active")
            .like(EnterpriseMemberRow::getDisplayName, LikePattern.escapeWildcards(search));
        if (after != null) {
            query.and(value -> value.lt(UserChannelBindingRow::getCreatedAt, after.time()).or(equal -> equal
                .eq(UserChannelBindingRow::getCreatedAt, after.time()).lt(UserChannelBindingRow::getId, after.id())));
        }
        query.orderByDesc(UserChannelBindingRow::getCreatedAt, UserChannelBindingRow::getId);
        var page = pagination.page(bindings.selectJoinPage(new Page<ChatTestMemberRow>(1, limit + 1L, false), ChatTestMemberRow.class, query).getRecords(),
            limit, scope, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        return new PageResponse<>(page.items().stream().map(row -> new Choice(row.getId(), row.getName(), row.getUsername())).toList(), page.nextCursor(), page.hasMore());
    }
}
