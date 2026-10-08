package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.IntegrationConfigurationMapper;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.response.IntegrationView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 管理查询返回脱敏视图；列表批量查询是否配置密钥，不逐条读取密文。
 */
@Service
public class IntegrationQueryService {
    private final EnterpriseIntegrationMapper connections;
    private final IntegrationSecretService secrets;
    private final IntegrationProviderRegistry providers;
    private final IntegrationPublicUrls urls;
    private final IntegrationManagementPolicy policy;
    private final ListPagination pagination;
    private final IntegrationConfigurationMapper configuration;

    public IntegrationQueryService(EnterpriseIntegrationMapper connections, IntegrationSecretService secrets,
                                    IntegrationProviderRegistry providers, IntegrationPublicUrls urls,
                                    IntegrationManagementPolicy policy, ListPagination pagination,
                                    IntegrationConfigurationMapper configuration) {
        this.connections = connections;
        this.secrets = secrets;
        this.providers = providers;
        this.urls = urls;
        this.policy = policy;
        this.pagination = pagination;
        this.configuration = configuration;
    }

    public PageResponse<IntegrationView> list(UserEntity actor, String enterprise, boolean system, String cursor, Integer count) {
        policy.require(actor, enterprise, system, "integration.view", false);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.id(), enterprise, system ? "system/integrations" : "integrations", "", "created_desc");
        var position = pagination.read(cursor, binding);
        var criteria = new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .ne(EnterpriseIntegrationRow::getStatus, "deleted").orderByDesc(EnterpriseIntegrationRow::getCreatedAt, EnterpriseIntegrationRow::getId);
        if (position != null) {
            criteria.and(group -> group.lt(EnterpriseIntegrationRow::getCreatedAt, position.time()).or(equal -> equal
                .eq(EnterpriseIntegrationRow::getCreatedAt, position.time()).lt(EnterpriseIntegrationRow::getId, position.id())));
        }
        var rows = connections.selectPage(new Page<EnterpriseIntegrationRow>(1, limit + 1, false), criteria).getRecords();
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        var configured = secrets.configured(enterprise, page.items().stream().map(EnterpriseIntegrationRow::getId).toList());
        return new PageResponse<>(page.items().stream().map(row -> view(row, configured.contains(row.getId()))).toList(),
            page.nextCursor(), page.hasMore());
    }

    public IntegrationView get(UserEntity actor, String enterprise, boolean system, String id) {
        policy.require(actor, enterprise, system, "integration.view", false);
        return view(require(enterprise, id));
    }

    public EnterpriseIntegrationRow require(String enterprise, String id) {
        var row = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, id)
            .ne(EnterpriseIntegrationRow::getStatus, "deleted"));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "INTEGRATION_UNAVAILABLE", "接入不存在或无法访问。");
        }
        return row;
    }

    public IntegrationApplication application(EnterpriseIntegrationRow row) {
        return new IntegrationApplication(row.getId(), row.getEnterpriseId(), row.getProviderCode(), row.getExternalTenantId(),
            row.getExternalAppId(), row.getCredentialRevision());
    }

    public IntegrationView view(EnterpriseIntegrationRow row) {
        return view(row, secrets.configured(row.getEnterpriseId(), row.getId()));
    }

    private IntegrationView view(EnterpriseIntegrationRow row, boolean configured) {
        return new IntegrationView(row.getId(), row.getEnterpriseId(), row.getProviderCode(), providers.require(row.getProviderCode()).name(),
            row.getName(), row.getExternalTenantId(), row.getExternalAppId(), row.getStatus(), Boolean.TRUE.equals(row.getBindingEnabled()),
            Boolean.TRUE.equals(row.getLoginEnabled()), Boolean.TRUE.equals(row.getMessagingEnabled()), configured,
            urls.callback(row.getId()).toString(), urls.login(row.getPublicLoginKey()).toString(), row.getLastCheckStatus(),
            time(row.getLastCheckedAt()), row.getLastCheckErrorSummary(), Long.toString(row.getRevision()),
            time(row.getCreatedAt()), time(row.getUpdatedAt()), configuration.read(row));
    }

    private String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
