package com.stonewu.agenteam.mapper.permission;

import com.stonewu.agenteam.model.enterprise.response.EnterpriseRoleResponse;
import com.stonewu.agenteam.model.enterprise.response.PermissionResponse;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import com.stonewu.agenteam.model.permission.entity.PermissionQueryRow;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 所有企业授权查询同时约束企业、成员、角色状态和角色权限关联。
 */
@Repository
public class PermissionMapper {

    private final PermissionSqlMapper statements;

    private final SysRolePermissionTableMapper sysRolePermissionTableMapper;

    private final SysPermissionTableMapper sysPermissionTableMapper;

    private final MemberRoleQueryMapper memberRoleQueryMapper;

    public PermissionMapper(PermissionSqlMapper statements, SysRolePermissionTableMapper sysRolePermissionTableMapper,
                            SysPermissionTableMapper sysPermissionTableMapper,
                            MemberRoleQueryMapper memberRoleQueryMapper) {
        this.memberRoleQueryMapper = memberRoleQueryMapper;
        this.sysPermissionTableMapper = sysPermissionTableMapper;
        this.sysRolePermissionTableMapper = sysRolePermissionTableMapper;
        this.statements = statements;
    }

    public List<String> listPermissionCodes(String userId, String enterpriseId) {
        return memberRoleQueryMapper.listPermissionCodesSysUserRole(userId, enterpriseId);
    }

    public Optional<DataScope> operationScope(String userId, String enterpriseId, String permission) {
        Integer rank = DataAccessUtils.nullableSingleResult(
            statements.operationScopeSysUserRole(userId, enterpriseId, permission));
        return Optional.ofNullable(rank).map(DataScope::fromRank);
    }

    public boolean isEnterpriseAdmin(String userId, String enterpriseId) {
        Integer count = DataAccessUtils.nullableSingleResult(
            memberRoleQueryMapper.isEnterpriseAdminSysUserRole(userId, enterpriseId));
        return count != null && count > 0;
    }

    public int activeAdministratorCount(String enterpriseId) {
        return DataAccessUtils.nullableSingleResult(statements.activeAdministratorCountSysUserRole(enterpriseId));
    }

    public boolean sharesActiveTeam(String enterpriseId, String userId, String ownerUserId) {
        return statements.countSharedTeamMembers(new OwnerQueryScope(enterpriseId, userId, "team"), ownerUserId) > 0;
    }

    public List<PermissionResponse> listPermissions() {
        return sysPermissionTableMapper.listPermissionsSysPermission().stream().map(this::mapPermission).toList();
    }

    public Optional<EnterpriseRoleResponse> findRole(String enterpriseId, String roleId) {
        return statements.findRoleSysRole(enterpriseId, roleId).stream().map(rows -> mapRole(rows, enterpriseId))
            .findFirst();
    }

    public String builtinRoleId(String enterpriseId, String code) {
        return DataAccessUtils.nullableSingleResult(statements.builtinRoleIdSysRole(enterpriseId, code));
    }

    public List<String> listUserRoleIds(String userId, String enterpriseId) {
        return memberRoleQueryMapper.listUserRoleIdsSysUserRole(enterpriseId, userId);
    }

    public void insertRole(String roleId, String enterpriseId, String code, String name, String description,
                           DataScope scope, boolean builtin, Instant now) {
        statements.insertRoleSysRole(roleId, enterpriseId, code, name, name.toLowerCase(Locale.ROOT), description,
            scope.code(), builtin, Timestamp.from(now));
    }

    @Transactional
    public void replaceRolePermissions(String enterpriseId, String roleId, Set<String> permissionCodes) {
        sysRolePermissionTableMapper.clearRolePermissions(enterpriseId, roleId);
        if (!permissionCodes.isEmpty()) {
            var rows = permissionCodes.stream().sorted().map(code -> {
                var row = new SysRolePermissionRow();
                row.setEnterpriseId(enterpriseId);
                row.setRoleId(roleId);
                row.setPermissionCode(code);
                return row;
            }).toList();
            sysRolePermissionTableMapper.insert(rows, 100);
        }
    }

    @Transactional
    public void replaceUserRoles(String userId, String enterpriseId, Set<String> roleIds, Instant now) {
        Set<String> current = new HashSet<>(listUserRoleIds(userId, enterpriseId));
        for (String roleId : current) {
            if (!roleIds.contains(roleId)) {
                memberRoleQueryMapper.replaceUserRolesSysUserRole(enterpriseId, userId, roleId);
            }
        }
        for (String roleId : roleIds) {
            if (!current.contains(roleId)) {
                memberRoleQueryMapper.addUserRole(enterpriseId, userId, roleId, Timestamp.from(now));
            }
        }
    }

    private EnterpriseRoleResponse mapRole(PermissionQueryRow rows, String enterpriseId) {
        String roleId = rows.getId();
        List<String> codes = sysRolePermissionTableMapper.mapRoleSysRolePermission(enterpriseId, roleId);
        return new EnterpriseRoleResponse(roleId, rows.getCode(), rows.getName(), rows.getBuiltin(), codes,
            rows.getDescription(), rows.getDataScope(), rows.getStatus(), rows.getRevision());
    }

    private PermissionResponse mapPermission(PermissionQueryRow rows) {
        return new PermissionResponse(rows.getCode(), rows.getName(), rows.getScope(), rows.getMenuKey(),
            rows.getMenuLabel(), rows.getMenuPath(), rows.getSortNo());
    }
}
