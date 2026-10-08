package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/** 发送测试准备已通过独立授权测试的应用与绑定，所有数据仍由正式实体和 Mapper 保存。 */
final class ChannelDeliveryFixtures {
    private final TransactionTemplate tx;
    private final IntegrationSecretService secrets;
    private final IntegrationQueryService queries;
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;

    ChannelDeliveryFixtures(TransactionTemplate tx, IntegrationSecretService secrets, IntegrationQueryService queries,
                             EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings) {
        this.tx = tx;
        this.secrets = secrets;
        this.queries = queries;
        this.connections = connections;
        this.bindings = bindings;
    }

    EnterpriseIntegrationRow configure(String enterprise, String user, String provider) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode(provider);
        row.setName("通知测试接入");
        row.setExternalTenantId("test-tenant-" + row.getId());
        row.setExternalAppId(provider.equals("wecom") ? "1000001" : "cli_" + row.getId());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setStatus("enabled");
        row.setBindingEnabled(true);
        row.setMessagingEnabled(true);
        row.setIdentityVerifiedAt(Instant.now());
        row.setLastCheckStatus("passed");
        row.setCreatedBy(user);
        row.setUpdatedBy(user);
        connections.insert(row);
        tx.executeWithoutResult(status -> secrets.store(enterprise, row.getId(), "test-notification-secret", user, 1, Instant.now()));
        var binding = new UserChannelBindingRow();
        binding.setId(UUID.randomUUID().toString());
        binding.setEnterpriseId(enterprise);
        binding.setConnectionId(row.getId());
        binding.setUserId(user);
        binding.setExternalSubjectType(provider.equals("wecom") ? "wecom_userid" : "feishu_open_id");
        binding.setExternalSubjectId(provider.equals("wecom") ? "member_001" : "ou_test_member");
        binding.setAuthorizedAt(Instant.now());
        bindings.insert(binding);
        return queries.require(enterprise, row.getId());
    }
}
