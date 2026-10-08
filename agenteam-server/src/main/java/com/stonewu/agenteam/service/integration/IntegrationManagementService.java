package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.IntegrationConfigurationMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationUpdateRequest;
import com.stonewu.agenteam.model.integration.response.IntegrationView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationCheckService.PreparedCheck;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.notification.ChannelDeliveryCancellation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配置、加密密钥和审计一起提交；外部网络校验由独立服务在事务外完成。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IntegrationManagementService {
    private final EnterpriseIntegrationMapper connections;
    private final IntegrationManagementPolicy policy;
    private final IntegrationQueryService queries;
    private final IntegrationSecretService secrets;
    private final IntegrationProviderRegistry providers;
    private final AuditEventService audit;
    private final Clock clock;
    private final IntegrationConfigurationMapper configuration;
    private final ChannelDeliveryCancellation cancellations;

    public IntegrationManagementService(EnterpriseIntegrationMapper connections, IntegrationManagementPolicy policy,
                                        IntegrationQueryService queries, IntegrationSecretService secrets,
                                        IntegrationProviderRegistry providers, AuditEventService audit, Clock clock,
                                        IntegrationConfigurationMapper configuration, ChannelDeliveryCancellation cancellations) {
        this.connections = connections;
        this.policy = policy;
        this.queries = queries;
        this.secrets = secrets;
        this.providers = providers;
        this.audit = audit;
        this.clock = clock;
        this.configuration = configuration;
        this.cancellations = cancellations;
    }

    public IntegrationView create(UserEntity actor, String enterprise, boolean system, IntegrationCreateRequest input) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var provider = providers.require(input.providerCode());
        String app = identifier(input.externalAppId(), "externalAppId", true);
        String tenant = identifier(input.externalTenantId(), "externalTenantId",
            provider.publicFields().stream().anyMatch(field -> field.name().equals("externalTenantId") && field.required()));
        provider.validateApplicationId(app);
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode(input.providerCode());
        row.setConfigJson(configuration.encode(row.getProviderCode(), input.configuration()));
        row.setName(ResourceInput.text(input.name(), "name", 80, true));
        row.setExternalAppId(app);
        row.setExternalTenantId(tenant);
        row.setBindingEnabled(Boolean.TRUE.equals(input.bindingEnabled()));
        row.setLoginEnabled(Boolean.TRUE.equals(input.loginEnabled()));
        row.setMessagingEnabled(Boolean.TRUE.equals(input.messagingEnabled()));
        row.setPublicLoginKey(UUID.randomUUID().toString().replace("-", ""));
        row.setCreatedBy(actor.id());
        row.setUpdatedBy(actor.id());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        try {
            connections.insert(row);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_ALREADY_CONFIGURED", "此企业应用已有接入配置，请维护已有配置。", duplicate);
        }
        secrets.store(enterprise, row.getId(), input.secret(), actor.id(), 1, clock.instant());
        audit.record(enterprise, actor, "integration.create", "integration", row.getId(), "创建企业接入配置",
            Map.of("providerCode", input.providerCode()));
        return queries.view(queries.require(enterprise, row.getId()));
    }

    public IntegrationView update(UserEntity actor, String enterprise, boolean system, String id,
                                   long revision, IntegrationUpdateRequest input) {
        var row = current(actor, enterprise, system, id, revision, "integration.manage");
        var changes = changes(row, actor);
        if (input.name() != null) {
            changes.set(EnterpriseIntegrationRow::getName, ResourceInput.text(input.name(), "name", 80, true));
        }
        if (input.bindingEnabled() != null) {
            changes.set(EnterpriseIntegrationRow::getBindingEnabled, input.bindingEnabled());
        }
        if (input.loginEnabled() != null) {
            changes.set(EnterpriseIntegrationRow::getLoginEnabled, input.loginEnabled());
        }
        if (input.messagingEnabled() != null) {
            changes.set(EnterpriseIntegrationRow::getMessagingEnabled, input.messagingEnabled());
        }
        if (input.configuration() != null) {
            changes.set(EnterpriseIntegrationRow::getConfigJson, configuration.encode(row.getProviderCode(), input.configuration()));
        }
        save(row, changes);
        if (Boolean.FALSE.equals(input.messagingEnabled())) {
            cancellations.cancel(enterprise, id, null, null, null);
        }
        audit.record(enterprise, actor, "integration.update", "integration", id, "更新企业接入名称与能力设置", Map.of());
        return queries.view(queries.require(enterprise, id));
    }

    public IntegrationView rotate(UserEntity actor, String enterprise, boolean system, String id, long revision, String secret) {
        var row = current(actor, enterprise, system, id, revision, "integration.manage");
        long nextCredential = row.getCredentialRevision() + 1;
        secrets.store(enterprise, id, secret, actor.id(), nextCredential, clock.instant());
        save(row, changes(row, actor).set(EnterpriseIntegrationRow::getCredentialRevision, nextCredential)
            .set(EnterpriseIntegrationRow::getStatus, row.getStatus().equals("enabled") ? "disabled" : row.getStatus())
            .set(EnterpriseIntegrationRow::getLastCheckStatus, "not_checked")
            .set(EnterpriseIntegrationRow::getLastCheckedAt, null).set(EnterpriseIntegrationRow::getLastCheckedRevision, null)
            .set(EnterpriseIntegrationRow::getLastCheckErrorCode, null).set(EnterpriseIntegrationRow::getLastCheckErrorSummary, null));
        audit.record(enterprise, actor, "integration.rotate_secret", "integration", id, "更换应用密钥并等待重新校验", Map.of());
        cancellations.cancel(enterprise, id, null, null, null);
        return queries.view(queries.require(enterprise, id));
    }

    public IntegrationView status(UserEntity actor, String enterprise, boolean system, String id, long revision, String status) {
        var row = current(actor, enterprise, system, id, revision, "integration.manage");
        if (!Set.of("enabled", "disabled").contains(status == null ? "" : status)) {
            throw ApiException.invalidField("status", "请选择启用或停用接入。");
        }
        if (status.equals("enabled") && (!row.getLastCheckStatus().equals("passed") || row.getIdentityVerifiedAt() == null
            || row.getExternalTenantId() == null || !secrets.configured(enterprise, id))) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_CHECK_REQUIRED", "请先校验当前应用密钥与配置，再启用接入。");
        }
        save(row, changes(row, actor).set(EnterpriseIntegrationRow::getStatus, status));
        if (status.equals("disabled")) {
            cancellations.cancel(enterprise, id, null, null, null);
        }
        audit.record(enterprise, actor, "integration.status", "integration", id,
            status.equals("enabled") ? "启用企业接入" : "停用企业接入", Map.of("status", status));
        return queries.view(queries.require(enterprise, id));
    }

    public void delete(UserEntity actor, String enterprise, boolean system, String id, long revision) {
        var row = current(actor, enterprise, system, id, revision, "integration.manage");
        if (row.getStatus().equals("enabled")) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_ENABLED", "请先停用接入，再执行删除。");
        }
        cancellations.requireNoActive(enterprise, id);
        save(row, changes(row, actor).set(EnterpriseIntegrationRow::getStatus, "deleted")
            .set(EnterpriseIntegrationRow::getDeletedAt, clock.instant()).set(EnterpriseIntegrationRow::getDeletedToken, id));
        audit.record(enterprise, actor, "integration.delete", "integration", id, "删除已停用的企业接入配置", Map.of());
    }

    public IntegrationView recordCheck(UserEntity actor, boolean system, PreparedCheck check) {
        var row = current(actor, check.enterpriseId(), system, check.connectionId(), check.revision(), "integration.test");
        var update = changes(row, actor).set(EnterpriseIntegrationRow::getLastCheckedAt, clock.instant())
            .set(EnterpriseIntegrationRow::getLastCheckedRevision, row.getRevision() + 1)
            .set(EnterpriseIntegrationRow::getLastCheckStatus, check.result() == null ? "failed" : "passed")
            .set(EnterpriseIntegrationRow::getLastCheckErrorCode, check.errorCode())
            .set(EnterpriseIntegrationRow::getLastCheckErrorSummary, check.errorSummary());
        if (check.result() != null) {
            update.set(EnterpriseIntegrationRow::getExternalTenantId, check.result().tenantId())
                .set(EnterpriseIntegrationRow::getIdentityVerifiedAt, clock.instant());
        }
        try {
            save(row, update);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_ALREADY_CONFIGURED", "此企业应用已配置，请使用已有接入。", duplicate);
        }
        audit.record(row.getEnterpriseId(), actor, "integration.check", "integration", row.getId(),
            check.result() == null ? "应用校验未通过" : "应用校验通过", Map.of("passed", check.result() != null));
        return queries.view(queries.require(row.getEnterpriseId(), row.getId()));
    }

    private EnterpriseIntegrationRow current(UserEntity actor, String enterprise, boolean system, String id, long revision, String permission) {
        policy.require(actor, enterprise, system, permission, true);
        var row = queries.require(enterprise, id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        return row;
    }

    private LambdaUpdateWrapper<EnterpriseIntegrationRow> changes(EnterpriseIntegrationRow row, UserEntity actor) {
        return new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, row.getEnterpriseId())
            .eq(EnterpriseIntegrationRow::getId, row.getId()).eq(EnterpriseIntegrationRow::getRevision, row.getRevision())
            .ne(EnterpriseIntegrationRow::getStatus, "deleted").set(EnterpriseIntegrationRow::getUpdatedBy, actor.id())
            .set(EnterpriseIntegrationRow::getUpdatedAt, clock.instant()).set(EnterpriseIntegrationRow::getRevision, row.getRevision() + 1);
    }

    private void save(EnterpriseIntegrationRow row, LambdaUpdateWrapper<EnterpriseIntegrationRow> changes) {
        if (connections.update(changes) != 1) {
            throw ApiException.versionConflict(row.getRevision());
        }
    }

    private String identifier(String value, String field, boolean required) {
        if (!required && value == null) {
            return null;
        }
        String text = ResourceInput.text(value, field, 191, required);
        if (text != null && !text.isEmpty() && !text.matches("[A-Za-z0-9_-]+")) {
            throw ApiException.invalidField(field, "请输入平台提供的完整编号，不要包含空格或其他符号。");
        }
        return text == null || text.isEmpty() ? null : text;
    }
}
