package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.i18n.Messages;

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
                throw invalid(Messages.text("error.enterprise.roleAssignmentPolicy.validateAssignments.1"));
            }
            validateDelegation(context, Set.copyOf(role.permissionCodes()), DataScope.fromCode(role.dataScope()));
            if (role.builtin() && role.code().equals("enterprise-admin") && !permissions.isEnterpriseAdmin(
                context.userId(), context.enterpriseId())) {
                throw forbidden(Messages.text("error.enterprise.roleAssignmentPolicy.validateAssignments.2"));
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
                throw forbidden(Messages.text("error.enterprise.roleAssignmentPolicy.validateDelegation.1"));
            }
            DataScope held = permissions.operationScope(context.userId(), context.enterpriseId(), code)
                .orElseThrow(() -> forbidden(Messages.text("error.permission.cannotDelegate")));
            if (!held.covers(requestedScope)) {
                throw forbidden(Messages.text("error.enterprise.roleAssignmentPolicy.validateDelegation.2"));
            }
        }
    }

    public Set<String> requestedPermissions(List<String> values) {
        if (values == null || values.size() > 200 || values.stream()
            .anyMatch(value -> value == null || value.isBlank())) {
            throw invalid(Messages.text("error.enterprise.roleAssignmentPolicy.requestedPermissions.1"));
        }
        Set<String> available = permissions.listPermissions().stream().map(PermissionResponse::code)
            .filter(catalog::availablePermission)
            .collect(Collectors.toSet());
        Set<String> requested = new HashSet<>(values);
        if (requested.size() != values.size() || !available.containsAll(requested)) {
            throw invalid(Messages.text("error.enterprise.roleAssignmentPolicy.requestedPermissions.2"));
        }
        return Set.copyOf(requested);
    }

    private EnterpriseRoleResponse findRole(String enterpriseId, String id) {
        return permissions.findRole(enterpriseId, id).orElseThrow(() -> missing(Messages.text("error.role.notFound")));
    }
}
