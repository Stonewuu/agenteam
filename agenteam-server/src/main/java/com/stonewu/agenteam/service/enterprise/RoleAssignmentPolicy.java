package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseRoleResponse;
import com.stonewu.agenteam.model.enterprise.response.PermissionResponse;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.permission.BuiltinRoleCatalog;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.stonewu.agenteam.service.enterprise.EnterpriseValidation.*;

/**
 * 检查角色分配与权限委派，防止授予自己没有的权限或数据范围。
 */
@Service
public class RoleAssignmentPolicy {
    private final PermissionMapper permissions;
    private final BuiltinRoleCatalog catalog;

    public RoleAssignmentPolicy(PermissionMapper permissions, BuiltinRoleCatalog catalog) {
        this.permissions = permissions;
        this.catalog = catalog;
    }

    public void validateAssignments(AuthContext context, Set<String> roleIds) {
        for (String id : roleIds) {
            EnterpriseRoleResponse role = findRole(context.enterpriseId(), id);
            if (!"active".equals(role.status())) {
                throw invalid("不能分配已停用的角色");
            }
            validateDelegation(context, Set.copyOf(role.permissionCodes()), DataScope.fromCode(role.dataScope()));
            if (role.builtin() && role.code().equals("enterprise-admin") && !permissions.isEnterpriseAdmin(
                context.userId(), context.enterpriseId())) {
                throw forbidden("只有企业管理员可以分配管理员角色");
            }
        }
    }

    public void validateExistingRoleAuthority(AuthContext context, List<String> roleIds) {
        for (String id : roleIds) {
            var role = findRole(context.enterpriseId(), id);
            validateDelegation(context, Set.copyOf(role.permissionCodes()), DataScope.fromCode(role.dataScope()));
        }
    }

    public void validateDelegation(AuthContext context, Set<String> codes, DataScope requestedScope) {
        boolean administrator = permissions.isEnterpriseAdmin(context.userId(), context.enterpriseId());
        for (String code : codes) {
            if (catalog.protectedPermission(code) && !administrator) {
                throw forbidden("只有企业管理员可以委派受保护的管理权限");
            }
            DataScope held = permissions.operationScope(context.userId(), context.enterpriseId(), code)
                .orElseThrow(() -> forbidden("不能分配自己没有的权限"));
            if (!held.covers(requestedScope)) {
                throw forbidden("不能分配超出自身数据范围的权限");
            }
        }
    }

    public Set<String> requestedPermissions(List<String> values) {
        if (values == null || values.size() > 200 || values.stream()
            .anyMatch(value -> value == null || value.isBlank())) {
            throw invalid("请提供有效的角色权限列表，最多两百项");
        }
        Set<String> available = permissions.listPermissions().stream().map(PermissionResponse::code)
            .filter(catalog::availablePermission)
            .collect(Collectors.toSet());
        Set<String> requested = new HashSet<>(values);
        if (requested.size() != values.size() || !available.containsAll(requested)) {
            throw invalid("权限列表包含重复或不存在的操作");
        }
        return Set.copyOf(requested);
    }

    private EnterpriseRoleResponse findRole(String enterpriseId, String id) {
        return permissions.findRole(enterpriseId, id).orElseThrow(() -> missing("角色不存在"));
    }
}
