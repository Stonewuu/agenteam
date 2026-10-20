package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundAttemptMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundAttemptRow;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.ChannelRateLimit;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.ChannelRateLimiter;
import com.stonewu.agenteam.model.chat.entity.ChatReceiptLease;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 聊天与协作测试共用真实隔离数据库、应用身份和消息构造。 */
@Import(SharedEnterpriseTestEdition.class)
abstract class ChatApiTestSupport extends ExecutionApiTestSupport {
    @Autowired protected ChatIngressService ingress;
    @Autowired protected ChatNormalizationTransactions normalization;
    @Autowired protected ChatConversationService conversations;
    @Autowired protected ChatBotBindingService bots;
    @Autowired protected ChatPayloadCipher cipher;
    @Autowired protected ChatConfiguration configuration;
    @Autowired protected ChatOutboundService outbound;
    @Autowired protected ChatSendTransactions sending;
    @Autowired protected ChannelRateLimiter rates;
    protected ChatBotBindingRow bot;
    protected String externalChat;

    @BeforeEach
    void fixture() {
        externalChat = "oc_" + UUID.randomUUID();
        var app = new EnterpriseIntegrationRow();
        app.setId(UUID.randomUUID().toString());
        app.setEnterpriseId(enterprise);
        app.setProviderCode("feishu");
        app.setName("仅用于隔离消息测试");
        app.setExternalAppId("cli_" + UUID.randomUUID());
        app.setExternalTenantId("tenant_" + enterprise);
        app.setPublicLoginKey(UUID.randomUUID().toString());
        app.setCreatedBy(admin);
        app.setUpdatedBy(admin);
        app.setStatus("enabled");
        app.setIdentityVerifiedAt(Instant.now());
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).insert(app);
        bot = ChatRecords.initialize(new ChatBotBindingRow(), enterprise, Instant.now());
        bot.setConnectionId(app.getId());
        bot.setPlatformBotId("ou_bot");
        bot.setAgentId(agent);
        bot.setPinnedVersionId(version);
        bot.setRoleName("人事");
        bot.setRoleDescription("消息测试岗位");
        bot.setStatus("enabled");
        bot.setTransport("websocket");
        bot.setChatEnabled(true);
        bot.setLeaseVersion(1L);
        bot.setLeaseOwner("test-connection");
        bot.setLeaseUntil(Instant.now().plusSeconds(300));
        databaseAccess.mapper(ChatBotBindingMapper.class).insert(bot);
    }

    protected ChatConversationRow enableGroup() {
        var group = conversations.observe(bot, bots.application(bot), externalChat, "测试群", "group", "platform_query");
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getStatus, "enabled"));
        databaseAccess.mapper(ChatConversationBotMapper.class).update(new LambdaUpdateWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getConversationId, group.getId()).set(ChatConversationBotRow::getReceiveEnabled, true)
            .set(ChatConversationBotRow::getSendEnabled, true).set(ChatConversationBotRow::getCollaborationEnabled, true));
        return group;
    }

    protected ChatConversationRow conversation() {
        return databaseAccess.mapper(ChatConversationMapper.class).selectOne(new LambdaQueryWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getExternalConversationId, externalChat));
    }

    protected ObjectNode message(String eventId, String messageId, String kind, String text) {
        var event = json.createObjectNode();
        event.putObject("header").put("event_type", "im.message.receive_v1").put("event_id", eventId);
        var body = event.putObject("event");
        body.putObject("sender").put("sender_type", kind).putObject("sender_id").put("open_id", kind.equals("user") ? "ou_user" : "ou_bot");
        var message = body.putObject("message").put("chat_type", "group").put("chat_id", externalChat)
            .put("message_id", messageId).put("message_type", "text").put("create_time", Long.toString(Instant.now().toEpochMilli()));
        message.put("content", json.valueToTree(Map.of("text", text)).toString());
        message.putArray("mentions").addObject().put("name", "人事").putObject("id").put("open_id", "ou_bot");
        return event;
    }

    protected void normalizeAll() {
        for (int index = 0; index < 50; index++) {
            var lease = normalization.claim("test-worker");
            if (lease.isEmpty()) {
                return;
            }
            normalization.normalize(lease.get());
        }
        fail("测试接收工作未能收敛");
    }

    protected ChatInboundReceiptMapper receipts() {
        return databaseAccess.mapper(ChatInboundReceiptMapper.class);
    }

    protected ChatMessageMapper messages() {
        return databaseAccess.mapper(ChatMessageMapper.class);
    }

    protected long receiptCount() {
        return receipts().selectCount(new LambdaQueryWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, enterprise));
    }

    protected long messageCount() {
        return messages().selectCount(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise));
    }
}
