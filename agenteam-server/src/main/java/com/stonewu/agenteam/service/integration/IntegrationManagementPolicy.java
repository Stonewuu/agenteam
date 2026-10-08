package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 全局管理与企业管理分别验证，不能为超级管理员伪造成员身份。
 */
@Service
public class IntegrationManagementPolicy {
    private final AccountBehaviorService behavior;
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final PermissionMapper permissions;

    public IntegrationManagementPolicy(AuthMapper users, EnterpriseMapper enterprises, PermissionMapper permissions, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.users = users;
        this.enterprises = enterprises;
        this.permissions = permissions;
    }

    public void require(UserEntity actor, String enterprise, boolean system, String permission, boolean lock) {
        var current = users.findById(actor.id()).orElseThrow(this::denied);
        if (!current.status().equals("active") || current.sessionVersion() != actor.sessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "登录状态已变化，请重新登录。");
        }
        behavior.requireOperation(current.id(), AccountOperation.MANAGE_INTEGRATION);
        if (system) {
            if (!current.superAdmin()) {
                throw denied();
            }
        } else if (!users.isActiveMember(actor.id(), enterprise) || !permissions.isEnterpriseAdmin(actor.id(), enterprise)
            || permissions.operationScope(actor.id(), enterprise, permission).filter(DataScope.ENTERPRISE::equals).isEmpty()) {
            throw denied();
        }
        String status = lock ? enterprises.lockEnterprise(enterprise).orElseThrow(this::missing)
            : enterprises.findById(enterprise).orElseThrow(this::missing).status();
        if (lock && !status.equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "ENTERPRISE_DISABLED", "企业已停用，请先恢复企业后再修改接入。");
        }
        // 锁等待期间成员或角色可能改变，写入前重新检查最新授权。
        if (lock && !system && (!users.isActiveMember(actor.id(), enterprise)
            || !permissions.isEnterpriseAdmin(actor.id(), enterprise)
            || permissions.operationScope(actor.id(), enterprise, permission).filter(DataScope.ENTERPRISE::equals).isEmpty())) {
            throw denied();
        }
        if (lock && system && users.findById(actor.id()).filter(user -> user.superAdmin() && user.status().equals("active")
            && user.sessionVersion() == actor.sessionVersion()).isEmpty()) {
            throw denied();
        }
    }

    private ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "INTEGRATION_MANAGEMENT_DENIED", "只有企业管理员或系统超级管理员可以维护此接入。");
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "ENTERPRISE_UNAVAILABLE", "当前企业不存在或无法访问。");
    }
}
