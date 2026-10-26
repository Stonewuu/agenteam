package com.stonewu.agenteam.service.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementOauthRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** 授权消费、凭据保存和审计在同一事务中完成。 */
@Service
public class FeishuManagementAuthorizationTransactions {
    private final FeishuSetupStore setup;
    private final FeishuManagementOauthStore flows;
    private final FeishuManagementGrantStore grants;
    private final IntegrationManagementPolicy policy;
    private final AuditEventService audit;

    public FeishuManagementAuthorizationTransactions(FeishuSetupStore setup, FeishuManagementOauthStore flows,
            FeishuManagementGrantStore grants, IntegrationManagementPolicy policy, AuditEventService audit) {
        this.setup = setup;
        this.flows = flows;
        this.grants = grants;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional
    public void complete(FeishuManagementOauthRow flow, UserEntity actor, JsonNode result) {
        policy.require(actor, flow.getEnterpriseId(), flow.getSystemAccess(), "integration.manage", true);
        var manager = setup.manager(flow.getEnterpriseId(), flow.getConnectionId());
        if (!manager.getCredentialRevision().equals(flow.getCredentialRevision())) {
            throw FeishuManagementOauthStore.unavailable();
        }
        grants.authorize(flow.getEnterpriseId(), manager, actor.id(), result);
        flows.finish(flow.getId(), true);
        audit.record(flow.getEnterpriseId(), actor, "feishu.management_authorized", "integration", manager.getId(),
            "授权群策管理飞书应用", Map.of());
    }
}
