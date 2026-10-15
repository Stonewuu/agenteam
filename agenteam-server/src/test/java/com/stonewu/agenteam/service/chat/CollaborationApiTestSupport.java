package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 协作任务测试共用真实企业成员、雇佣和应用身份。 */
abstract class CollaborationApiTestSupport extends ChatApiTestSupport {
    @Autowired protected ChatRoutingService routing;
    @Autowired protected CollaborationConfiguration collaboration;
    @Autowired protected CollaborationAccessService taskAccess;
    protected UserEntity initiator;
    protected UserChannelBindingRow binding;
    protected ChatConversationRow group;

    @BeforeEach
    void userFixture() {
        collaboration.setEnabled(true);
        initiator = EnterpriseTestData.member(users, permissions, enterprise, "chat_" + UUID.randomUUID().toString().substring(0, 8),
            "chat-isolated-user-password", "真实任务发起人", List.of(permissions.builtinRoleId(enterprise, "member")));
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterprise)
            .eq(ResourceRow::getId, agent).set(ResourceRow::getOwnerUserId, initiator.id()));
        hires.establish(enterprise, initiator.id(), agent, Instant.now());
        binding = new UserChannelBindingRow();
        binding.setId(UUID.randomUUID().toString());
        binding.setEnterpriseId(enterprise);
        binding.setConnectionId(bot.getConnectionId());
        binding.setUserId(initiator.id());
        binding.setExternalSubjectType("feishu_open_id");
        binding.setExternalSubjectId("ou_user");
        binding.setChatEnabled(true);
        binding.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(binding);
        group = enableGroup();
    }

    protected ChatMessageRow receive(String id, String text) {
        ingress.accept(enterprise, bot.getId(), 1L, message(id, id, "user", text));
        normalizeAll();
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getPlatformMessageId, id));
    }

    protected ChatMessageRow onlyMessage() {
        return messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise));
    }

    protected CollaborationTaskRow onlyTask() {
        assertEquals(1, taskCount());
        return databaseAccess.mapper(CollaborationTaskMapper.class).selectOne(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise));
    }

    protected long taskCount() {
        return databaseAccess.mapper(CollaborationTaskMapper.class).selectCount(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise));
    }

    protected ChatBotBindingRow secondBot() {
        return additionalBot("ou_finance", "财务");
    }

    protected ChatBotBindingRow additionalBot(String platformId, String roleName) {
        var app = databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectById(bot.getConnectionId());
        app.setId(UUID.randomUUID().toString());
        app.setExternalAppId("cli_other_" + UUID.randomUUID());
        app.setPublicLoginKey(UUID.randomUUID().toString());
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).insert(app);
        var finance = databaseAccess.mapper(ChatBotBindingMapper.class).selectById(bot.getId());
        finance.setId(UUID.randomUUID().toString());
        finance.setConnectionId(app.getId());
        finance.setPlatformBotId(platformId);
        finance.setRoleName(roleName);
        databaseAccess.mapper(ChatBotBindingMapper.class).insert(finance);
        conversations.observe(finance, bots.application(finance), externalChat, "测试群", "group", "platform_query");
        databaseAccess.mapper(ChatConversationBotMapper.class).update(new LambdaUpdateWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getConversationId, group.getId()).eq(ChatConversationBotRow::getBotBindingId, finance.getId())
            .set(ChatConversationBotRow::getSendEnabled, true).set(ChatConversationBotRow::getCollaborationEnabled, true));
        return finance;
    }
}
