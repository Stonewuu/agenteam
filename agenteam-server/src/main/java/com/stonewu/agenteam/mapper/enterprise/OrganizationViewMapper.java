package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.permission.SysRolePermissionTableMapper;
import com.stonewu.agenteam.model.enterprise.entity.OrganizationViewQueryRow;
import com.stonewu.agenteam.model.enterprise.response.*;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 管理列表始终在数据库内先限制数据范围，再进行固定顺序分页。
 */
@Repository
public class OrganizationViewMapper {
    private final OrganizationViewSqlMapper statements;
    private final IdentityViewMapper identities;
    private final SysRolePermissionTableMapper rolePermissions;

    public OrganizationViewMapper(OrganizationViewSqlMapper statements, IdentityViewMapper identities,
                                  SysRolePermissionTableMapper rolePermissions) {
        this.statements = statements;
        this.identities = identities;
        this.rolePermissions = rolePermissions;
    }

    public List<EnterpriseView> enterprises(String userId, boolean systemAdministrator) {
        return statements.enterprisesEnterprise(systemAdministrator, userId).stream().map(this::mapEnterprise).toList();
    }

    public Optional<EnterpriseView> enterprise(String id) {
        return statements.enterpriseEnterprise(id).stream().map(this::mapEnterprise).findFirst();
    }

    public List<MemberView> members(String enterpriseId, String actorId, DataScope scope, String query, String teamId,
                                    String status, PagePosition after, int count) {
        var range = new OwnerQueryScope(enterpriseId, actorId, scope.code());
        return identities.members(enterpriseId, statements.memberIds(range, query, teamId, status, after, count));
    }

    public List<TeamView> teams(String enterpriseId, String actorId, DataScope scope, String query, PagePosition after,
                                int count) {
        return statements.listTeams(new OwnerQueryScope(enterpriseId, actorId, scope.code()), query, after, count)
            .stream().map(this::team).toList();
    }

    public Optional<TeamView> team(String enterpriseId, String id) {
        return statements.teamEnterpriseTeamMember(enterpriseId, id).stream().map(this::team).findFirst();
    }

    public List<RoleView> roles(String enterpriseId, String query, PagePosition after, int count) {
        return mapRoles(enterpriseId, statements.listRoles(enterpriseId, query, after, count));
    }

    public Optional<RoleView> role(String enterpriseId, String id) {
        return mapRoles(enterpriseId, statements.findRole(enterpriseId, id)).stream().findFirst();
    }

    private List<RoleView> mapRoles(String enterprise, List<OrganizationViewQueryRow> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        var permissions = new LinkedHashMap<String, List<String>>();
        for (var rows : rolePermissions.selectList(new LambdaQueryWrapper<SysRolePermissionRow>()
            .select(SysRolePermissionRow::getRoleId, SysRolePermissionRow::getPermissionCode)
            .eq(SysRolePermissionRow::getEnterpriseId, enterprise)
            .in(SysRolePermissionRow::getRoleId, selected.stream().map(OrganizationViewQueryRow::getId).toList())
            .orderByAsc(SysRolePermissionRow::getRoleId, SysRolePermissionRow::getPermissionCode))) {
            permissions.computeIfAbsent(rows.getRoleId(), ignored -> new ArrayList<>()).add(rows.getPermissionCode());
        }
        return selected.stream().map(rows -> role(rows, permissions.getOrDefault(rows.getId(), List.of()))).toList();
    }


    private EnterpriseView mapEnterprise(OrganizationViewQueryRow rows) {
        return new EnterpriseView(rows.getId(), rows.getRevision(), time(rows.getCreatedAt()),
            time(rows.getUpdatedAt()), rows.getName(),
            rows.getDescription(), rows.getContactEmail(), rows.getTimezone(), rows.getQuotaTimezone(),
            rows.getPendingQuotaTimezone(), rows.getRetentionDays(), rows.getStatus());
    }

    private TeamView team(OrganizationViewQueryRow rows) {
        return new TeamView(rows.getId(), rows.getRevision(), time(rows.getCreatedAt()), time(rows.getUpdatedAt()),
            rows.getName(),
            rows.getDescription(), new ActorView(rows.getOwnerUserId(), rows.getOwnerName()), rows.getStatus(),
            rows.getMemberCount());
    }

    private RoleView role(OrganizationViewQueryRow rows, List<String> permissions) {
        return new RoleView(rows.getId(), rows.getRevision(), time(rows.getCreatedAt()), time(rows.getUpdatedAt()),
            rows.getName(), rows.getCode(), rows.getDescription(), rows.getDataScope(),
            permissions, rows.getBuiltin(), rows.getStatus(), rows.getMemberCount());
    }

    private String time(Timestamp value) {
        return value.toInstant().toString();
    }
}
