package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * 修改权限与对象状态分别检查，重复请求在已经改变状态后仍可重新验证原操作者资格。
 */
@Service
public class ResourcePolicy {
    private final ResourceMapper resources;
    private final ResourceAuthorizationMapper records;
    private final ResourceAuthorizationService access;
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final Clock clock;

    public ResourcePolicy(ResourceMapper resources, ResourceAuthorizationMapper records,
                          ResourceAuthorizationService access,
                          EnterpriseAuthorizationService authorization, PermissionMapper permissions, Clock clock) {
        this.resources = resources;
        this.records = records;
        this.access = access;
        this.authorization = authorization;
        this.permissions = permissions;
        this.clock = clock;
    }

    public void authorizeCreation(AuthContext actor, ResourceKind kind) {
        authorization.lockAndRequire(actor, kind.permission("create"));
    }

    public ResourceRecord authorize(AuthContext actor, String id, String action, boolean mutation, boolean deleted) {
        ResourceRecord resource = resources.find(actor.enterpriseId(), id, deleted, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (mutation) {
            authorization.lockAndRequire(actor, resource.kind().permission(action));
        }
        var capability = action.equals("view") || action.equals(
            "export") ? ResourceCapability.VIEW : ResourceCapability.EDIT;
        var scope = access.scope(actor, resource.kind().code(), resource.kind().permission(action), capability,
            deleted);
        if (records.visible(id, scope).isEmpty()) {
            throw ResourceAuthorizationService.unavailable();
        }
        resource = resources.find(actor.enterpriseId(), id, deleted, mutation)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (resource.deletedAt() != null && !resource.deletedAt().plus(Duration.ofDays(30)).isAfter(clock.instant())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return resource;
    }

    public void revision(ResourceRecord resource, long expected) {
        if (resource.revision() != expected) {
            throw ApiException.versionConflict(resource.revision());
        }
    }

    public void editable(ResourceRecord resource) {
        if (resource.source().equals("builtin")) {
            throw new ApiException(HttpStatus.CONFLICT, "BUILTIN_RESOURCE_READ_ONLY",
                "内置" + resource.kind().label() + "不能直接修改，请先复制。");
        }
        if (resource.status().equals("deleted")) {
            throw ResourceAuthorizationService.unavailable();
        }
    }

    public Map<String, List<String>> actions(AuthContext actor, List<ResourceRecord> page) {
        Map<String, List<String>> result = new HashMap<>();
        if (page.isEmpty()) {
            return result;
        }
        boolean grantsAllowed = permissions.operationScope(actor.userId(), actor.enterpriseId(),
            "resource.grants.manage").isPresent();
        boolean maintainAll = permissions.operationScope(actor.userId(), actor.enterpriseId(), "resource.manage_all")
            .isPresent();
        boolean administrator = enterpriseAdmin(actor);
        for (ResourceKind kind : page.stream().map(ResourceRecord::kind).distinct().toList()) {
            var rows = page.stream().filter(item -> item.kind() == kind).toList();
            var ids = rows.stream().map(ResourceRecord::id).toList();
            Map<String, Set<String>> allowed = new HashMap<>();
            var operations = new ArrayList<>(List.of("view", "edit", "publish", "delete"));
            if (kind == ResourceKind.AGENT || kind == ResourceKind.WORKFLOW) {
                operations.add("preview");
            }
            if (kind == ResourceKind.PLUGIN) {
                operations.add("test");
            }
            if (kind == ResourceKind.SKILL) {
                operations.add("export");
            }
            for (String action : operations) {
                try {
                    var scope = access.scope(actor, kind.code(), kind.permission(action),
                        action.equals("view") || action.equals(
                            "export") ? ResourceCapability.VIEW : ResourceCapability.EDIT, true);
                    allowed.put(action, records.visibleIds(ids, scope));
                } catch (ResponseStatusException denied) {
                    if (denied.getStatusCode().value() != 403 && denied.getStatusCode().value() != 404) {
                        throw denied;
                    }
                    allowed.put(action, Set.of());
                }
            }
            boolean createAllowed = canCreate(actor, kind);
            for (var resource : rows) {
                String id = resource.id();
                boolean builtin = resource.source().equals("builtin");
                boolean edit = allowed.get("edit").contains(id);
                var actions = new ArrayList<String>();
                if (resource.deletedAt() != null) {
                    if (!builtin && allowed.get("delete").contains(id)) {
                        actions.add("restore");
                    }
                } else {
                    if ((kind == ResourceKind.AGENT || kind == ResourceKind.WORKFLOW) && resource.status()
                        .equals("active") && allowed.get("preview").contains(id)) {
                        actions.add("preview");
                    }
                    if (kind == ResourceKind.PLUGIN && allowed.get("test").contains(id)) {
                        actions.add("test");
                    }
                    if (kind == ResourceKind.SKILL && resource.publishedVersionId() != null && allowed.get("export")
                        .contains(id)) {
                        actions.add("export");
                    }
                    if (!builtin) {
                        if (edit) {
                            actions.add("edit");
                        }
                        if (allowed.get("publish").contains(id) && resource.status().equals("active")) {
                            actions.add("publish");
                        }
                        if (allowed.get("publish").contains(id)) {
                            actions.add("revoke");
                        }
                        if (allowed.get("delete").contains(id)) {
                            actions.add("delete");
                        }
                    }
                    if (edit && (!builtin || administrator)) {
                        actions.add("status");
                    }
                    if (createAllowed && allowed.get("view").contains(id)) {
                        actions.add("copy");
                    }
                    if (grantsAllowed && (actor.userId().equals(resource.ownerUserId()) || maintainAll)) {
                        actions.add("grants");
                        if (!builtin && edit) {
                            actions.add("transfer");
                        }
                    }
                    if (kind == ResourceKind.AGENT && !builtin && resource.publishedVersionId() != null && allowed.get(
                        "publish").contains(id)) {
                        actions.add("listing");
                    }
                }
                result.put(id, List.copyOf(actions));
            }
        }
        return result;
    }

    public boolean grantManager(AuthContext actor, ResourceRecord resource) {
        return permissions.operationScope(actor.userId(), actor.enterpriseId(), "resource.grants.manage").isPresent()
            && (actor.userId().equals(resource.ownerUserId()) || permissions.operationScope(actor.userId(),
            actor.enterpriseId(), "resource.manage_all").isPresent());
    }

    public boolean enterpriseAdmin(AuthContext actor) {
        return permissions.isEnterpriseAdmin(actor.userId(), actor.enterpriseId());
    }

    public boolean canCreate(AuthContext actor, ResourceKind kind) {
        return permissions.operationScope(actor.userId(), actor.enterpriseId(), kind.permission("create")).isPresent();
    }
}
