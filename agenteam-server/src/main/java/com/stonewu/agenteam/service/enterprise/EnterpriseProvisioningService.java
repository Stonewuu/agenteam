package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseSummary;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import com.stonewu.agenteam.service.edition.InstallationService;
import com.stonewu.agenteam.service.permission.BuiltinRoleCatalog;
import com.stonewu.agenteam.service.plugin.BuiltinPluginResourceService;
import com.stonewu.agenteam.service.usage.QuotaPolicyProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 新企业、指定管理员、五种内置角色和默认次数规则在同一事务建立。
 */
@Service
public class EnterpriseProvisioningService {
    private final AccountBehaviorService behavior;
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final PermissionMapper permissions;
    private final BuiltinRoleCatalog catalog;
    private final AuditEventService audit;
    private final BuiltinPluginResourceService builtinPlugins;
    private final EnterpriseEditionPolicy edition;
    private final InstallationService installation;
    private final QuotaPolicyProvider quotaPolicies;

    public EnterpriseProvisioningService(AuthMapper users, EnterpriseMapper enterprises, PermissionMapper permissions,
                                         BuiltinRoleCatalog catalog, AuditEventService audit,
                                         BuiltinPluginResourceService builtinPlugins, EnterpriseEditionPolicy edition,
                                         InstallationService installation, QuotaPolicyProvider quotaPolicies, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.users = users;
        this.enterprises = enterprises;
        this.permissions = permissions;
        this.catalog = catalog;
        this.audit = audit;
        this.builtinPlugins = builtinPlugins;
        this.edition = edition;
        this.installation = installation;
        this.quotaPolicies = quotaPolicies;
    }

    @Transactional
    public EnterpriseSummary create(String name, UserEntity actor, Instant now) {
        return create(name, "", null, "Asia/Shanghai", actor.id(), actor, now);
    }

    @Transactional
    public EnterpriseSummary create(String name, String description, String contactEmail, String timezone,
                                    String administratorId, UserEntity actor, Instant now) {
        return createVerified(name, description, contactEmail, timezone, administratorId, actor, now, false);
    }

    @Transactional
    public EnterpriseSummary createInitial(String name, String description, String contactEmail, String timezone,
                                           UserEntity actor, Instant now) {
        installation.lockBeforeBootstrap();
        installation.requireBootstrapAdministrator(actor.id());
        var created = createVerified(name, description, contactEmail, timezone, actor.id(), actor, now, true);
        installation.recordInitialEnterprise(created.enterpriseId(), now);
        return created;
    }

    private EnterpriseSummary createVerified(String name, String description, String contactEmail, String timezone,
                                             String administratorId, UserEntity actor, Instant now, boolean initial) {
        UserEntity current = users.findById(actor.id())
            .orElseThrow(() -> EnterpriseValidation.forbidden("无法创建企业"));
        if (!current.superAdmin() || !"active".equals(
            current.status()) || current.sessionVersion() != actor.sessionVersion()) {
            throw EnterpriseValidation.forbidden("只有有效的系统超级管理员可以创建企业");
        }
        String normalizedName = EnterpriseValidation.name(name, "企业名称", 80);
        UserEntity administrator = users.findById(administratorId).filter(user -> user.status().equals("active"))
            .orElseThrow(() -> EnterpriseValidation.invalid("请选择有效账号作为初始管理员"));
        behavior.requireOperation(administrator.id(), AccountOperation.SELECT_ENTERPRISE_ADMINISTRATOR);
        if (!initial) {
            edition.requireAdditionalCreation();
        }
        String id = UUID.randomUUID().toString();
        enterprises.insert(id, normalizedName, description, contactEmail, current.id(),
            QuotaPeriod.containing(now, timezone), now);
        users.addMember(id, administrator.id(), administrator.displayName(), now);
        Map<String, String> roles = catalog.createRoles(permissions, id, now);
        permissions.replaceUserRoles(administrator.id(), id, Set.of(roles.get("enterprise-admin")), now);
        enterprises.createDefaultQuota(id, quotaPolicies.initialEnterpriseLimit(), now);
        builtinPlugins.initialize(id);
        audit.record(id, current, "enterprise.create", "enterprise", id, "创建企业并设置初始管理员",
            Map.of("name", normalizedName, "createdBy", current.id(), "administratorUserId", administrator.id()));
        return new EnterpriseSummary(id, normalizedName);
    }
}
