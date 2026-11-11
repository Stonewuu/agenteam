package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 单项转交和成员移除使用相同接收人资格，成为所有者后仍须具备该类编辑权限。
 */
@Service
public class ResourceOwnershipPolicy {
    private final AuthMapper users;
    private final PermissionMapper permissions;

    public ResourceOwnershipPolicy(AuthMapper users, PermissionMapper permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    public boolean canTransferOwned(AuthContext actor, String owner, Set<ResourceKind> kinds) {
        if (kinds.isEmpty()) {
            return true;
        }
        return permitted(actor, "resource.grants.manage") && (actor.userId().equals(owner) || permitted(actor,
            "resource.manage_all"))
            && kinds.stream().allMatch(kind -> permitted(actor, kind.permission("edit")));
    }

    public String requireRecipient(String enterprise, String recipient, Set<ResourceKind> kinds, String field) {
        if (recipient == null || !users.isActiveMember(recipient, enterprise) || kinds.stream()
            .anyMatch(kind -> permissions.operationScope(recipient, enterprise, kind.permission("edit")).isEmpty())) {
            String label = kinds.isEmpty() ? Messages.text("text.resource.resourceOwnershipPolicy.requireRecipient.1") : String.join("、",
                kinds.stream().map(ResourceKind::label).toList());
            throw ApiException.invalidField(field, Messages.text("error.resource.resourceOwnershipPolicy.requireRecipient.1", label));
        }
        return users.findById(recipient).orElseThrow().id();
    }

    private boolean permitted(AuthContext actor, String permission) {
        return permissions.operationScope(actor.userId(), actor.enterpriseId(), permission).isPresent();
    }
}
