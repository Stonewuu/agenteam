package com.stonewu.agenteam.service.permission;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.EnterprisePermissionChanged;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.stonewu.agenteam.service.enterprise.EnterpriseValidation.forbidden;
import static com.stonewu.agenteam.service.enterprise.EnterpriseValidation.missing;

/**
 * 管理写入先锁定企业，再校验最新权限；权限版本和审计与业务变更一起提交。
 */
@Service
public class EnterpriseAuthorizationService {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final PermissionMapper permissions;
    private final AuditEventService audit;
    private final ApplicationEventPublisher events;
    private final AccountBehaviorService behavior;

    public EnterpriseAuthorizationService(AuthMapper users, EnterpriseMapper enterprises, PermissionMapper permissions,
                                          AuditEventService audit, ApplicationEventPublisher events,
                                          AccountBehaviorService behavior) {
        this.users = users;
        this.enterprises = enterprises;
        this.permissions = permissions;
        this.audit = audit;
        this.events = events;
        this.behavior = behavior;
    }

    public DataScope lockAndRequire(AuthContext context, String permission) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("企业管理写入必须处于事务中");
        }
        String status = enterprises.lockEnterprise(context.enterpriseId()).orElseThrow(() -> missing("企业不存在"));
        if (!"active".equals(status)) {
            throw forbidden("当前企业已停用");
        }
        return require(context, permission);
    }

    public DataScope require(AuthContext context, String permission) {
        var user = users.findById(context.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录"));
        if (!"active".equals(user.status()) || user.sessionVersion() != context.user()
            .sessionVersion() || !behavior.allowed(user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号状态已变化，请重新登录");
        }
        var scope = permissions.operationScope(context.userId(), context.enterpriseId(), permission)
            .orElseThrow(() -> forbidden("没有执行此操作的权限"));
        return behavior.permissionScope(user, context.enterpriseId(), permission, scope);
    }

    public void requireEnterpriseScope(DataScope scope) {
        if (scope != DataScope.ENTERPRISE) {
            throw forbidden("此操作需要企业范围的管理权限");
        }
    }

    public void requireOwner(AuthContext context, DataScope scope, String ownerUserId) {
        if (scope == DataScope.ENTERPRISE || context.userId().equals(ownerUserId)) {
            return;
        }
        if (scope == DataScope.TEAM && permissions.sharesActiveTeam(context.enterpriseId(), context.userId(),
            ownerUserId)) {
            return;
        }
        throw missing("记录不存在或无法访问");
    }

    public void changed(AuthContext context, String action, String objectType, String objectId, String summary,
                        Map<String, ?> detail) {
        if (permissions.activeAdministratorCount(context.enterpriseId()) < 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "企业至少需要保留一名有效管理员。");
        }
        enterprises.advancePermissionVersion(context.enterpriseId());
        audit.record(context.enterpriseId(), context.user(), action, objectType, objectId, summary, detail);
        events.publishEvent(new EnterprisePermissionChanged(context.enterpriseId()));
    }
}
