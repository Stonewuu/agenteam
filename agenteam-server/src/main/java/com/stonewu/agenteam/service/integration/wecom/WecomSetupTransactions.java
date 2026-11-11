package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.integration.WecomBotEnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.integration.entity.WecomBotEnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationCheckService.PreparedCheck;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomBotIdentityClient.Identity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** 创建凭据、平台企业核验记录与进度一起提交，避免重试产生重复机器人。 */
@Service
public class WecomSetupTransactions {
    private final WecomSetupStore store;
    private final WecomSetupCipher cipher;
    private final WecomEnterpriseConnections enterprises;
    private final IntegrationManagementService integrations;
    private final IntegrationQueryService queries;
    private final WecomBotEnterpriseMapper links;
    private final ChatBotBindingService bots;
    private final ResourceJson json;
    private final Clock clock;

    public WecomSetupTransactions(WecomSetupStore store, WecomSetupCipher cipher, WecomEnterpriseConnections enterprises,
            IntegrationManagementService integrations, IntegrationQueryService queries, WecomBotEnterpriseMapper links,
            ChatBotBindingService bots, ResourceJson json, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.enterprises = enterprises;
        this.integrations = integrations;
        this.queries = queries;
        this.links = links;
        this.bots = bots;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void install(WecomSetupJobRow claimed, UserEntity actor, Identity identity, String canonicalOwner) {
        var row = store.copy(claimed);
        var material = cipher.read(row);
        var input = store.input(row);
        var connection = integrations.create(actor, row.getEnterpriseId(), row.getSystemAccess(),
            new IntegrationCreateRequest("wecom_aibot", input.name(), null, material.botId(), material.secret(), false, false, false, Map.of(), "chat_bot"));
        // create 已锁定企业，核对等待锁期间是否发生了凭据变化；失败会回滚本事务的接入和密钥。
        var parent = enterprises.require(row.getEnterpriseId(), row.getEnterpriseConnectionId());
        if (!parent.getCredentialRevision().equals(row.getEnterpriseCredentialRevision())) {
            throw ApiException.versionConflict(parent.getRevision());
        }
        enterprises.requireAvailableName(row.getEnterpriseId(), input.name(), connection.id());
        integrations.recordCheck(actor, row.getSystemAccess(), new PreparedCheck(row.getEnterpriseId(), connection.id(),
            Long.parseLong(connection.revision()), new ChannelApplicationCheck(null, identity.name()), null, null));
        var checked = queries.require(row.getEnterpriseId(), connection.id());
        var link = new WecomBotEnterpriseRow();
        link.setId(checked.getId());
        link.setEnterpriseId(row.getEnterpriseId());
        link.setEnterpriseConnectionId(parent.getId());
        link.setExternalTenantId(parent.getExternalTenantId());
        link.setBotCredentialRevision(checked.getCredentialRevision());
        link.setEnterpriseCredentialRevision(parent.getCredentialRevision());
        link.setOwnerSubjectHash(json.hash(json.tree(List.of(parent.getExternalTenantId(), canonicalOwner))));
        link.setProofMethod("cli_owner_conversion");
        link.setVerifiedAt(clock.instant());
        links.insert(link);
        integrations.status(actor, row.getEnterpriseId(), row.getSystemAccess(), checked.getId(), checked.getRevision(), "enabled");
        row.setConnectionId(checked.getId());
        row.setPlatformName(identity.name());
        row.setEncryptedJson(null);
        row.setStage("binding");
        row.setNextAttemptAt(clock.instant());
        store.save(row);
    }

    @Transactional
    public void bind(WecomSetupJobRow claimed, UserEntity actor, ChatBotBindingService.PreparedIdentity prepared) {
        var row = store.copy(claimed);
        if (enterprises.verified(row.getEnterpriseId(), row.getConnectionId()).isEmpty()) {
            throw new IllegalStateException(Messages.text("error.integration.wecomSetupTransactions.bind.1"));
        }
        var input = store.input(row);
        var bot = bots.create(actor, row.getEnterpriseId(), row.getSystemAccess(), WecomSetupStore.binding(input, row.getConnectionId(), false), prepared);
        bots.update(actor, row.getEnterpriseId(), row.getSystemAccess(), bot.id(), bot.revision(), WecomSetupStore.binding(input, row.getConnectionId(), true));
        row.setBotId(bot.id());
        row.setStage("completed");
        row.setEncryptedJson(null);
        store.save(row);
    }
}
