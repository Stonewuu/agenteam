package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent.Kind;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/** 接收记录提交成功后才确认平台，未启用群和机器人消息不持久保存正文。 */
@Service
public class ChatIngressService {
    private final ChatBotBindingService bots;
    private final ChatPlatformRegistry platforms;
    private final ChatInboundReceiptMapper receipts;
    private final ChatConversationMapper conversations;
    private final ChatConversationService discovery;
    private final EnterpriseMapper enterprises;
    private final ChatMessageIdentity identities;
    private final ChatPayloadCipher cipher;
    private final ChatConfiguration configuration;
    private final Clock clock;
    private final ChatPairingService pairing;
    private final ChatOutboundService outbound;
    private final ChatCapabilityService capabilities;
    private final ChatGroupActivationService activation;

    public ChatIngressService(ChatBotBindingService bots, ChatPlatformRegistry platforms, ChatInboundReceiptMapper receipts,
                               ChatConversationMapper conversations, ChatConversationService discovery, EnterpriseMapper enterprises, ChatMessageIdentity identities,
                               ChatPayloadCipher cipher, ChatConfiguration configuration, Clock clock, ChatPairingService pairing,
                               ChatOutboundService outbound, ChatCapabilityService capabilities, ChatGroupActivationService activation) {
        this.bots = bots;
        this.platforms = platforms;
        this.receipts = receipts;
        this.conversations = conversations;
        this.discovery = discovery;
        this.enterprises = enterprises;
        this.identities = identities;
        this.cipher = cipher;
        this.configuration = configuration;
        this.clock = clock;
        this.pairing = pairing;
        this.outbound = outbound;
        this.capabilities = capabilities;
        this.activation = activation;
    }

    @Transactional
    public String acceptVerified(String enterprise, String botId, Long connectionLease, long credentialRevision, long bindingRevision,
            String transport, JsonNode envelope) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var bot = bots.require(enterprise, botId);
        var app = bots.application(bot);
        if (!bot.getTransport().equals(transport) || app.credentialRevision() != credentialRevision || bot.getRevision() != bindingRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CONNECTION_EXPIRED", "当前机器人接收配置已发生变化。");
        }
        return accept(enterprise, botId, connectionLease, envelope);
    }

    @Transactional
    public String accept(String enterprise, String botId, Long connectionLease, JsonNode envelope) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var bot = bots.require(enterprise, botId);
        var app = bots.application(bot);
        if (!configuration.enabled(app.providerCode()) || !bot.getStatus().equals("enabled")) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_DISABLED", "机器人消息接收已关闭。");
        }
        if (bot.getTransport().equals("websocket") && connectionLease == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CONNECTION_REQUIRED", "长连接接收必须提供当前连接凭据。");
        }
        if (connectionLease != null && (bot.getLeaseVersion() != connectionLease.longValue() || bot.getLeaseUntil() == null
            || !bot.getLeaseUntil().isAfter(clock.instant()))) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CONNECTION_EXPIRED", "当前机器人连接已由其他进程接管。");
        }
        var event = platforms.normalizer(app.providerCode()).normalize(app, bot.getPlatformBotId(), envelope, clock.instant());
        if (event.humanMessage() && event.externalChatId() != null) {
            var conversation = discovery.observe(bot, app, event.externalChatId(), null, event.chatKind(), "verified_message", event.platformCreatedAt());
            activation.activate(bot, conversation, event);
        }
        if (event.kind() == Kind.IGNORED && (event.deliveryId() == null || event.deliveryId().isBlank())) {
            return null;
        }
        String receiptKey = identities.receipt(event);
        var existing = receipts.selectOne(new LambdaQueryWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, enterprise)
            .eq(ChatInboundReceiptRow::getBotBindingId, botId).eq(ChatInboundReceiptRow::getReceiptKey, receiptKey));
        if (existing != null) {
            return existing.getId();
        }
        var row = ChatRecords.initialize(new ChatInboundReceiptRow(), enterprise, clock.instant());
        row.setBotBindingId(botId);
        row.setConnectionRevision(app.credentialRevision());
        row.setConnectionLeaseVersion(connectionLease == null ? 0L : connectionLease);
        row.setPlatformEventId(event.deliveryId());
        row.setReceiptKey(receiptKey);
        row.setEventType(event.eventType());
        row.setPlatformMessageId(event.messageId());
        row.setReceivedAt(clock.instant());
        row.setAttemptCount(0);
        row.setLeaseVersion(0L);
        if (capabilities.consume(bot, event)) {
            row.setStatus("ignored");
            row.setPayloadEncrypted(cipher.encrypt(enterprise, "receipt", row.getId(), Map.of("reason", "能力验证消息不触发任务")));
            row.setPayloadPurgedAt(clock.instant());
            receipts.insert(row);
            return row.getId();
        }
        if (pairing.command(app, event)) {
            String result = pairing.consume(app, event);
            row.setStatus("ignored");
            row.setPayloadEncrypted(cipher.encrypt(enterprise, "receipt", row.getId(), Map.of("reason", "账号配对消息不保留正文")));
            row.setPayloadPurgedAt(clock.instant());
            receipts.insert(row);
            if (event.chatKind().equals("direct")) {
                var conversation = discovery.observe(bot, app, event.externalChatId(), null, "direct", "verified_message", event.platformCreatedAt());
                outbound.text(enterprise, conversation.getId(), botId, null, null, "pairing:" + row.getId(), "binding", result, null);
            }
            return row.getId();
        }
        boolean keep = event.kind() != Kind.MESSAGE || event.humanMessage() && "direct".equals(event.chatKind());
        if (event.humanMessage() && "group".equals(event.chatKind())) {
            String scope = app.externalTenantId() == null ? "bot:" + botId : "tenant:" + app.externalTenantId();
            keep = conversations.selectCount(new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
                .eq(ChatConversationRow::getPlatformCode, app.providerCode()).eq(ChatConversationRow::getPlatformScopeKey, scope)
                .eq(ChatConversationRow::getExternalConversationId, event.externalChatId()).eq(ChatConversationRow::getStatus, "enabled")) > 0;
        }
        row.setStatus(keep ? "received" : "ignored");
        row.setPayloadEncrypted(cipher.encrypt(enterprise, "receipt", row.getId(), keep ? envelope : Map.of("reason", "不在允许的接收范围")));
        receipts.insert(row);
        return row.getId();
    }
}
