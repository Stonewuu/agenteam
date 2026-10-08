package com.stonewu.agenteam.service.permission;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceAccessRecord;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 公共资源授权不代替发布版本、雇佣和执行依赖验证，各业务在通过后继续检查自身约束。
 */
@Service
public class ResourceAuthorizationService {
    private static final Set<String> KINDS = Set.of("agent", "skill", "plugin", "workflow", "knowledge", "data");
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final ResourceAuthorizationMapper resources;
    private final AccountBehaviorService behavior;

    public ResourceAuthorizationService(EnterpriseAuthorizationService authorization, PermissionMapper permissions,
                                        ResourceAuthorizationMapper resources, AccountBehaviorService behavior) {
        this.authorization = authorization;
        this.permissions = permissions;
        this.resources = resources;
        this.behavior = behavior;
    }

    public ResourceAccessRecord require(AuthContext actor, String id, String operation, ResourceCapability capability) {
        int separator = operation.indexOf('.');
        if (separator < 1) {
            throw new IllegalArgumentException("资源操作需要明确类型");
        }
        return resources.visible(id, scope(actor, operation.substring(0, separator), operation, capability))
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public ResourceQueryScope scope(AuthContext actor, String kind, String operation, ResourceCapability capability) {
        return scope(actor, kind, operation, capability, false);
    }

    public ResourceQueryScope scope(AuthContext actor, String kind, String operation, ResourceCapability capability,
                                    boolean includeDeleted) {
        boolean workflowExecution = "workflow".equals(kind) && operation.equals(
            "agent.run") && capability == ResourceCapability.USE;
        if (!KINDS.contains(kind) || (!operation.startsWith(kind + ".") && !workflowExecution)) {
            throw new IllegalArgumentException("资源操作必须匹配资源类型");
        }
        if (includeDeleted && capability == ResourceCapability.USE) {
            throw new IllegalArgumentException("已删除资源不能用于执行");
        }
        var range = authorization.require(actor, operation);
        boolean maintainAll = capability != ResourceCapability.USE
            && permissions.operationScope(actor.userId(), actor.enterpriseId(), "resource.manage_all").isPresent();
        return behavior.resourceScope(actor, new ResourceQueryScope(actor.enterpriseId(), actor.userId(), kind,
            range.code(), capability.code(), maintainAll, includeDeleted));
    }

    public ResourceAccessRecord requireUse(AuthContext actor, String id, String kind) {
        return resources.visible(id, usageScope(actor, kind)).orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public ResourceQueryScope usageScope(AuthContext actor, String kind) {
        // 工作流由智能体执行，没有单独的普通成员运行权限；仍独立验证工作流的使用授权。
        String operation = switch (kind) {
            case "agent", "workflow" -> "agent.run";
            case "skill" -> "skill.use";
            case "plugin" -> "plugin.invoke";
            case "knowledge" -> "knowledge.search";
            case "data" -> "data.query";
            default -> throw new IllegalArgumentException("不支持的资源类型");
        };
        return scope(actor, kind, operation, ResourceCapability.USE);
    }

    public ResourceAccessRecord requireGrantManager(AuthContext actor, String id, boolean lock) {
        authorization.require(actor, "resource.grants.manage");
        var resource = resources.find(actor.enterpriseId(), id, lock)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!resource.ownerUserId().equals(actor.userId()) && permissions.operationScope(actor.userId(),
            actor.enterpriseId(), "resource.manage_all").isEmpty()) {
            throw unavailable();
        }
        return resource;
    }

    public ResourceAccessRecord requireGrantReader(AuthContext actor, String id) {
        if (actor.permissions().contains("resource.grants.manage") || !actor.permissions()
            .contains("resource.grants.view")) {
            return requireGrantManager(actor, id, false);
        }
        authorization.require(actor, "resource.grants.view");
        var resource = resources.find(actor.enterpriseId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return require(actor, id, resource.kind() + ".view", ResourceCapability.VIEW);
    }

    public void requirePrivateOwner(AuthContext actor, String objectEnterpriseId, String ownerId, String operation) {
        if (!actor.enterpriseId().equals(objectEnterpriseId) || !actor.userId().equals(ownerId)) {
            throw unavailable();
        }
        authorization.require(actor, operation);
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "记录不存在或无法访问。");
    }
}
