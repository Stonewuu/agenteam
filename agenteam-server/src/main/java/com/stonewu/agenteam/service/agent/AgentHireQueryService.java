package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentHireApplicationMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.model.agent.response.AgentHireApplicationView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * 申请人仅查看本人记录，审批人还可读取其实际资源维护范围内的申请。
 */
@Service
public class AgentHireQueryService {
    private final AgentHireApplicationMapper applications;
    private final PermissionMapper permissions;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;
    private final ListPagination pagination;
    private final Clock clock;
    private final ResourceAuthorizationMapper grants;

    public AgentHireQueryService(AgentHireApplicationMapper applications, PermissionMapper permissions,
                                 EnterpriseAuthorizationService authorization,
                                 ResourceAuthorizationService resources, ListPagination pagination, Clock clock,
                                 ResourceAuthorizationMapper grants) {
        this.applications = applications;
        this.permissions = permissions;
        this.authorization = authorization;
        this.resources = resources;
        this.pagination = pagination;
        this.clock = clock;
        this.grants = grants;
    }

    public PageResponse<AgentHireApplicationView> list(AuthContext actor, String cursor, Integer requestedLimit) {
        boolean own = permissions.operationScope(actor.userId(), actor.enterpriseId(), "agent.hire").isPresent();
        ResourceQueryScope approval = null;
        if (permissions.operationScope(actor.userId(), actor.enterpriseId(), "agent.hire_approve").isPresent()) {
            approval = resources.scope(actor, "agent", "agent.hire_approve", ResourceCapability.EDIT);
        } else if (own) {
            authorization.require(actor, "agent.hire");
        }
        if (!own && approval == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号不能查看雇佣申请。");
        }
        int limit = pagination.limit(requestedLimit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "hire-requests", "",
            "created_desc");
        var rows = applications.list(actor.enterpriseId(), actor.userId(), own, approval,
            pagination.read(cursor, binding), limit);
        var page = pagination.page(rows, limit, binding, value -> new PagePosition(value.createdAt(), value.id()));
        var now = clock.instant();
        Set<String> manageable = approval == null ? Set.of() : grants.visibleIds(
            page.items().stream().map(value -> value.agentId()).distinct().toList(), approval);
        return new PageResponse<>(page.items().stream().map(
            value -> applications.view(value, now, own && value.userId().equals(actor.userId()),
                manageable.contains(value.agentId()))).toList(), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentHireApplicationView get(AuthContext actor, String id) {
        var value = applications.find(actor.enterpriseId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        boolean own = actor.userId().equals(value.userId()) && permissions.operationScope(actor.userId(),
            actor.enterpriseId(), "agent.hire").isPresent();
        boolean approve = false;
        if (permissions.operationScope(actor.userId(), actor.enterpriseId(), "agent.hire_approve").isPresent()) {
            var scope = resources.scope(actor, "agent", "agent.hire_approve", ResourceCapability.EDIT);
            approve = grants.visibleIds(List.of(value.agentId()), scope).contains(value.agentId());
        }
        if (!own && !approve) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (own) {
            authorization.require(actor, "agent.hire");
        }
        return applications.view(value, clock.instant(), own, approve);
    }
}
