package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.OrganizationViewMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.response.MemberView;
import com.stonewu.agenteam.model.enterprise.response.PermissionView;
import com.stonewu.agenteam.model.enterprise.response.RoleView;
import com.stonewu.agenteam.model.enterprise.response.TeamView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.BuiltinRoleCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 组织读取按具体操作合并角色范围，不从另一个权限借用更大范围。
 */
@Service
public class OrganizationQueryService {
    private final EnterpriseAuthorizationService authorization;
    private final OrganizationViewMapper views;
    private final EnterpriseMapper members;
    private final IdentityViewMapper identities;
    private final PermissionMapper permissions;
    private final ListPagination pagination;
    private final BuiltinRoleCatalog catalog;

    public OrganizationQueryService(EnterpriseAuthorizationService authorization, OrganizationViewMapper views,
                                    EnterpriseMapper members,
                                    IdentityViewMapper identities, PermissionMapper permissions,
                                    ListPagination pagination, BuiltinRoleCatalog catalog) {
        this.authorization = authorization;
        this.views = views;
        this.members = members;
        this.identities = identities;
        this.permissions = permissions;
        this.pagination = pagination;
        this.catalog = catalog;
    }

    public PageResponse<MemberView> members(AuthContext actor, String teamId, String cursor, Integer requestedLimit,
                                            String search, String status) {
        var scope = authorization.require(actor, teamId == null ? "enterprise.members.view" : "enterprise.teams.view");
        if (teamId != null) {
            team(actor, teamId);
        }
        int limit = pagination.limit(requestedLimit);
        String query = pagination.query(search);
        if (status != null && !Set.of("active", "disabled", "removed").contains(status)) {
            throw ApiException.invalidField("status", "请选择有效的成员状态。");
        }
        var binding = binding(actor, teamId == null ? "members" : "teams/" + teamId + "/members",
            query + "\nstatus=" + (status == null ? "" : status));
        // 团队接口的数据范围约束团队所有者；通过团队检查后读取该团队的完整成员关系。
        var rows = views.members(actor.enterpriseId(), actor.userId(), teamId == null ? scope : DataScope.ENTERPRISE,
            query, teamId, status, pagination.read(cursor, binding), limit + 1);
        return pagination.page(rows, limit, binding,
            member -> new PagePosition(Instant.parse(member.joinedAt()), member.userId()));
    }

    public MemberView member(AuthContext actor, String id) {
        var scope = authorization.require(actor, "enterprise.members.view");
        authorization.requireOwner(actor, scope, id);
        if (members.findMember(actor.enterpriseId(), id).isEmpty()) {
            throw missing("成员");
        }
        return identities.member(actor.enterpriseId(), id);
    }

    public PageResponse<TeamView> teams(AuthContext actor, String cursor, Integer requestedLimit, String search) {
        var scope = authorization.require(actor, "enterprise.teams.view");
        int limit = pagination.limit(requestedLimit);
        String query = pagination.query(search);
        var binding = binding(actor, "teams", query);
        var rows = views.teams(actor.enterpriseId(), actor.userId(), scope, query, pagination.read(cursor, binding),
            limit + 1);
        return pagination.page(rows, limit, binding,
            team -> new PagePosition(Instant.parse(team.createdAt()), team.id()));
    }

    public TeamView team(AuthContext actor, String id) {
        var scope = authorization.require(actor, "enterprise.teams.view");
        var team = views.team(actor.enterpriseId(), id).orElseThrow(() -> missing("团队"));
        authorization.requireOwner(actor, scope, team.owner().id());
        return team;
    }

    public PageResponse<RoleView> roles(AuthContext actor, String cursor, Integer requestedLimit, String search) {
        authorization.requireEnterpriseScope(authorization.require(actor, "enterprise.roles.view"));
        int limit = pagination.limit(requestedLimit);
        String query = pagination.query(search);
        var binding = binding(actor, "roles", query);
        var rows = views.roles(actor.enterpriseId(), query, pagination.read(cursor, binding), limit + 1);
        return pagination.page(rows, limit, binding,
            role -> new PagePosition(Instant.parse(role.createdAt()), role.id()));
    }

    public RoleView role(AuthContext actor, String id) {
        authorization.requireEnterpriseScope(authorization.require(actor, "enterprise.roles.view"));
        return views.role(actor.enterpriseId(), id).orElseThrow(() -> missing("角色"));
    }

    public List<PermissionView> permissions(AuthContext actor) {
        authorization.require(actor, "enterprise.permissions.view");
        return permissions.listPermissions().stream()
            .filter(permission -> catalog.availablePermission(permission.code()))
            .map(permission -> new PermissionView(permission.code(), permission.name(), permission.scope())).toList();
    }

    private ListPagination.Binding binding(AuthContext actor, String collection, String query) {
        return new ListPagination.Binding(actor.userId(), actor.enterpriseId(), collection, query, "created_desc");
    }

    private ApiException missing(String label) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", label + "不存在或无法访问。");
    }
}
