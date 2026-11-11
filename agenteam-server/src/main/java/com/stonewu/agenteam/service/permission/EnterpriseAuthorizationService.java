package com.stonewu.agenteam.service.permission;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw new IllegalStateException(Messages.text("error.permission.enterpriseAuthorizationService.lockAndRequire.1"));
        }
        String status = enterprises.lockEnterprise(context.enterpriseId()).orElseThrow(() -> missing(Messages.text("error.enterprise.notFound")));
        if (!"active".equals(status)) {
            throw forbidden(Messages.text("error.permission.enterpriseAuthorizationService.lockAndRequire.2"));
        }
        return require(context, permission);
    }

    public DataScope require(AuthContext context, String permission) {
        var user = users.findById(context.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.text("error.permission.enterpriseAuthorizationService.require.1")));
        if (!"active".equals(user.status()) || user.sessionVersion() != context.user()
            .sessionVersion() || !behavior.allowed(user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.text("error.permission.enterpriseAuthorizationService.require.2"));
        }
        var scope = permissions.operationScope(context.userId(), context.enterpriseId(), permission)
            .orElseThrow(() -> forbidden(Messages.text("error.permission.denied")));
        return behavior.permissionScope(user, context.enterpriseId(), permission, scope);
    }

    public void requireEnterpriseScope(DataScope scope) {
        if (scope != DataScope.ENTERPRISE) {
            throw forbidden(Messages.text("error.permission.enterpriseAuthorizationService.requireEnterpriseScope.1"));
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
        throw missing(Messages.text("error.permission.enterpriseAuthorizationService.requireOwner.1"));
    }

    public void changed(AuthContext context, String action, String objectType, String objectId, String summary,
                        Map<String, ?> detail) {
        if (permissions.activeAdministratorCount(context.enterpriseId()) < 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", Messages.text("error.permission.enterpriseAuthorizationService.changed.1"));
        }
        enterprises.advancePermissionVersion(context.enterpriseId());
        audit.record(context.enterpriseId(), context.user(), action, objectType, objectId, summary, detail);
        events.publishEvent(new EnterprisePermissionChanged(context.enterpriseId()));
    }
}
