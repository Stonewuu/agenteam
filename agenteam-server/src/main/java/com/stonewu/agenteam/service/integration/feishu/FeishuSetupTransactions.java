package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationCheckService.PreparedCheck;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** 接入、密钥或岗位绑定与创建任务进度同时提交，重复处理不会留下重复绑定。 */
@Service
public class FeishuSetupTransactions {
    private final FeishuSetupStore store;
    private final IntegrationManagementService integrations;
    private final ChatBotBindingService bots;
    private final IntegrationQueryService queries;
    private final FeishuManagementConnections managementConnections;

    public FeishuSetupTransactions(FeishuSetupStore store, IntegrationManagementService integrations, ChatBotBindingService bots, IntegrationQueryService queries,
                                  FeishuManagementConnections managementConnections) {
        this.store = store;
        this.integrations = integrations;
        this.bots = bots;
        this.queries = queries;
        this.managementConnections = managementConnections;
    }

    @Transactional
    public void install(FeishuSetupJobRow row, UserEntity actor, String tenantId, String tenantName, String confirmedName) {
        row = store.copy(row);
        store.applyConfirmedName(row, confirmedName);
        var input = store.input(row);
        var material = store.material(row);
        var connection = integrations.create(actor, row.getEnterpriseId(), row.getSystemAccess(),
            new IntegrationCreateRequest("feishu", input.name(), tenantId, material.appId(), material.secret(),
                row.getKind().equals("bot"), false, false, Map.of(), row.getKind().equals("management") ? "app_management"
                    : row.getKind().equals("enterprise") ? "enterprise" : "chat_bot"));
        integrations.recordCheck(actor, row.getSystemAccess(), new PreparedCheck(row.getEnterpriseId(),
            connection.id(), Long.parseLong(connection.revision()), new ChannelApplicationCheck(tenantId, tenantName), null, null));
        row.setConnectionId(connection.id());
        var checked = queries.require(row.getEnterpriseId(), connection.id());
        row.setConnectionRevision(checked.getRevision());
        row.setCredentialRevision(checked.getCredentialRevision());
        row.setEncryptedJson(null);
        row.setStage("configuring");
        store.save(row);
    }

    @Transactional
    public void prepareExisting(FeishuSetupJobRow row, UserEntity actor, String tenantId, String tenantName, String confirmedName) {
        row = store.copy(row);
        store.applyConfirmedName(row, confirmedName);
        var checked = integrations.recordCheck(actor, row.getSystemAccess(), new PreparedCheck(row.getEnterpriseId(),
            row.getConnectionId(), row.getConnectionRevision(), new ChannelApplicationCheck(tenantId, tenantName), null, null));
        row.setConnectionRevision(Long.parseLong(checked.revision()));
        row.setStage("configuring");
        store.save(row);
    }

    @Transactional
    public void approved(FeishuSetupJobRow row, UserEntity actor) {
        row = store.copy(row);
        var connection = queries.require(row.getEnterpriseId(), row.getConnectionId());
        if (!connection.getRevision().equals(row.getConnectionRevision()) || !connection.getCredentialRevision().equals(row.getCredentialRevision())) {
            throw ApiException.versionConflict(connection.getRevision());
        }
        if (row.getKind().equals("enterprise")) {
            var input = store.input(row);
            integrations.configureFeishuEnterprise(actor, row.getEnterpriseId(), row.getSystemAccess(), connection.getId(), connection.getRevision(),
                input.bindingEnabled() == null && input.connectionId() == null ? Boolean.TRUE : input.bindingEnabled(),
                input.loginEnabled() == null && input.connectionId() == null ? Boolean.TRUE : input.loginEnabled(),
                input.messagingEnabled() == null && input.connectionId() == null ? Boolean.TRUE : input.messagingEnabled());
            connection = queries.require(row.getEnterpriseId(), row.getConnectionId());
        }
        integrations.status(actor, row.getEnterpriseId(), row.getSystemAccess(), connection.getId(), connection.getRevision(), "enabled");
        connection = queries.require(row.getEnterpriseId(), row.getConnectionId());
        row.setConnectionRevision(connection.getRevision());
        if (row.getKind().equals("enterprise")) {
            managementConnections.record(connection);
        }
        row.setStage(row.getKind().equals("bot") ? "binding" : "completed");
        store.save(row);
    }

    @Transactional
    public void bind(FeishuSetupJobRow row, UserEntity actor, ChatBotBindingService.PreparedIdentity prepared) {
        row = store.copy(row);
        var input = store.input(row);
        var bot = bots.create(actor, row.getEnterpriseId(), row.getSystemAccess(),
            FeishuSetupStore.binding(input, row.getConnectionId(), false), prepared);
        bots.update(actor, row.getEnterpriseId(), row.getSystemAccess(), bot.id(), bot.revision(),
            FeishuSetupStore.binding(input, row.getConnectionId(), true));
        row.setBotId(bot.id());
        row.setStage("completed");
        row.setEncryptedJson(null);
        store.save(row);
    }
}
