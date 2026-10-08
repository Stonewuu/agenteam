package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.enterprise.MemberRemovalMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberRemovalRecipientMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.response.MemberRemovalImpactView;
import com.stonewu.agenteam.model.enterprise.response.MemberRemovalRecipientView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MemberRemovalQueryService {
    private final MemberRemovalPolicy policy;
    private final MemberRemovalMapper records;
    private final MemberRemovalTokenService tokens;
    private final MemberRemovalRecipientMapper recipients;
    private final ListPagination pagination;

    public MemberRemovalQueryService(MemberRemovalPolicy policy, MemberRemovalMapper records,
                                     MemberRemovalTokenService tokens, MemberRemovalRecipientMapper recipients,
                                     ListPagination pagination) {
        this.policy = policy;
        this.records = records;
        this.tokens = tokens;
        this.recipients = recipients;
        this.pagination = pagination;
    }

    public MemberRemovalImpactView impact(AuthContext actor, String member) {
        member = policy.authorize(actor, member, false, false);
        var state = records.load(actor.enterpriseId(), member).orElseThrow(ResourceAuthorizationService::unavailable);
        var token = tokens.issue(actor, member, state);
        return new MemberRemovalImpactView(token.value(), token.expiresAt().toString(),
            Long.toString(state.memberRevision()), state.resources().size(), state.ownedTeams().size(),
            state.openTodos().size(),
            state.activeRuns().size(), state.enabledSchedules().size(), state.lastAdministrator(),
            policy.canTransferOwnership(actor, member, state));
    }

    public PageResponse<MemberRemovalRecipientView> recipients(AuthContext actor, String member, String kind,
                                                               String query, String cursor, Integer size) {
        member = policy.authorize(actor, member, false, false);
        if (kind == null || !Set.of("ownership", "todo").contains(kind)) {
            throw ApiException.invalidField("kind", "请选择资源负责人或待办接收人。");
        }
        var state = records.load(actor.enterpriseId(), member).orElseThrow(ResourceAuthorizationService::unavailable);
        if (kind.equals("ownership") && !policy.canTransferOwnership(actor, member, state)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前权限不能完成这些资源或团队的交接。");
        }
        var required = kind.equals("todo") ? List.of("todo.view", "todo.manage") : state.resourceKinds().stream()
            .map(value -> value.permission("edit")).sorted().toList();
        String search = pagination.query(query);
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "member-removal/" + member,
            kind + "|" + search, "joined_desc");
        var page = pagination.page(recipients.list(actor.enterpriseId(), member, required, kind.equals("todo"), search,
                pagination.read(cursor, binding), limit), limit, binding,
            row -> new PagePosition(row.joinedAt(), row.id()));
        return new PageResponse<>(
            page.items().stream().map(row -> new MemberRemovalRecipientView(row.id(), row.name())).toList(),
            page.nextCursor(), page.hasMore());
    }
}
