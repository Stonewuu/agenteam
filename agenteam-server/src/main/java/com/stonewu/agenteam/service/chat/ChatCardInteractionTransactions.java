package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationReplyModeService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** 卡片操作必须对应本应用真正发出的消息，重复平台事件只返回已保存结果。 */
@Service
public class ChatCardInteractionTransactions {
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ChatIdentityService identities;
    private final ChatOutboundMessageMapper messages;
    private final ChatInboundReceiptMapper receipts;
    private final EnterpriseMapper enterprises;
    private final CollaborationAccessService access;
    private final CollaborationReplyModeService modes;
    private final ChatOutboundService outbound;
    private final ChatCapabilityService capabilities;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;

    public ChatCardInteractionTransactions(ChatBotBindingService bots, ChatConversationService conversations,
            ChatIdentityService identities, ChatOutboundMessageMapper messages, ChatInboundReceiptMapper receipts,
            EnterpriseMapper enterprises, CollaborationAccessService access, CollaborationReplyModeService modes,
            ChatOutboundService outbound, ChatCapabilityService capabilities, ChatPayloadCipher cipher, ResourceJson json, Clock clock) {
        this.bots = bots;
        this.conversations = conversations;
        this.identities = identities;
        this.messages = messages;
        this.receipts = receipts;
        this.enterprises = enterprises;
        this.access = access;
        this.modes = modes;
        this.outbound = outbound;
        this.capabilities = capabilities;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(timeout = 2)
    public JsonNode accept(String enterprise, String botId, Long lease, long credentialRevision, long bindingRevision, JsonNode envelope) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var bot = bots.require(enterprise, botId);
        var app = bots.application(bot);
        if (!"enabled".equals(bot.getStatus()) || !"feishu".equals(app.providerCode()) || bot.getRevision() != bindingRevision
                || app.credentialRevision() != credentialRevision || !app.externalAppId().equals(envelope.path("header").path("app_id").asText())
                || "websocket".equals(bot.getTransport()) && (lease == null || bot.getLeaseVersion() != lease.longValue()
                    || bot.getLeaseUntil() == null || !bot.getLeaseUntil().isAfter(clock.instant()))) {
            throw unavailable();
        }
        String eventId = envelope.path("header").path("event_id").asText();
        if (!eventId.matches("[A-Za-z0-9_-]{1,191}")) {
            throw unavailable();
        }
        String key = json.hash(json.tree(List.of("card-action", eventId)));
        var previous = receipts.selectOne(new LambdaQueryWrapper<ChatInboundReceiptRow>().eq(ChatInboundReceiptRow::getEnterpriseId, enterprise)
            .eq(ChatInboundReceiptRow::getBotBindingId, botId).eq(ChatInboundReceiptRow::getReceiptKey, key));
        if (previous != null) {
            return previous.getPayloadPurgedAt() != null ? toast("info", "此操作已处理，请重新打开设置。")
                : cipher.decrypt(enterprise, "receipt", previous.getId(), previous.getPayloadEncrypted()).path("response");
        }
        var event = envelope.path("event");
        var context = event.path("context");
        var source = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).eq(ChatOutboundMessageRow::getBotBindingId, botId)
            .eq(ChatOutboundMessageRow::getPlatformMessageId, context.path("open_message_id").asText())
            .eq(ChatOutboundMessageRow::getStatus, "sent").in(ChatOutboundMessageRow::getKind, "accepted", "reply_settings", "card_probe"));
        if (source == null) {
            throw unavailable();
        }
        var group = conversations.require(enterprise, source.getConversationId());
        var member = conversations.member(enterprise, group.getId(), botId);
        var original = cipher.decrypt(enterprise, "outbound", source.getId(), source.getPayloadEncrypted());
        if (!"enabled".equals(group.getStatus()) || member == null || !"present".equals(member.getMembershipStatus())
                || !Boolean.TRUE.equals(member.getSendEnabled()) || !original.path("receiveId").asText().equals(context.path("open_chat_id").asText())
                || original.path("credentialRevision").asLong(-1) != credentialRevision) {
            throw unavailable();
        }
        var binding = identities.find(enterprise, bot.getConnectionId(), "feishu_open_id", event.path("operator").path("open_id").asText(),
            event.path("operator").path("union_id").asText(null)).orElseThrow(() ->
                new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_BINDING_REQUIRED", "请先在群策绑定当前飞书账号并开启机器人聊天。"));
        var actor = identities.actor(enterprise, binding.getUserId(), binding.getId());
        String action = event.path("action").path("value").path("action").asText();
        JsonNode response;
        if ("reply_mode_probe".equals(action) && "card_probe".equals(source.getKind()) && original.path("replyCardProbe").asBoolean()
                && source.getCreatedAt().plusSeconds(600).isAfter(clock.instant())) {
            capabilities.evidence(bot, "cardInteraction", Map.of("status", "passed", "checkedAt", clock.instant().toString(), "outboundId", source.getId()));
            response = toast("success", "卡片按钮已可以使用。");
        } else {
            if (source.getTaskId() == null || source.getRenderedPayloadEncrypted() == null
                    || !cipher.decrypt(enterprise, "outbound-rendered", source.getId(), source.getRenderedPayloadEncrypted()).hasNonNull("replySettingsUrl")) {
                throw unavailable();
            }
            var task = access.require(enterprise, source.getTaskId());
            if (!task.getInitiatorUserId().equals(actor.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "COLLABORATION_REPLY_OWNER_REQUIRED", "仅发起人可以修改此任务的回复方式。");
            }
            access.owned(actor, task.getId());
            response = switch (action) {
                case "reply_mode_open", "reply_mode_close" -> {
                    outbound.updateSettingsCard(source, "card-action:" + eventId, "reply_mode_open".equals(action));
                    yield JsonNodeFactory.instance.objectNode();
                }
                case "reply_mode_save" -> {
                    var value = event.path("action").path("value");
                    if (!value.path("expectedRevision").canConvertToLong() || !value.path("expectedDefaultRevision").canConvertToLong()) {
                        throw unavailable();
                    }
                    var saved = modes.save(actor, task.getId(), new CollaborationReplyModeRequest(value.path("outputVisibility").asText(),
                        value.path("expectedRevision").asLong(), event.path("action").path("form_value").path("saveDefault").asBoolean(false),
                        value.path("expectedDefaultRevision").asLong()));
                    yield toast("success", "chat_full".equals(saved.outputVisibility()) ? "本任务将直接回复内容。" : "本任务将仅发送链接。");
                }
                default -> throw unavailable();
            };
        }
        var receipt = ChatRecords.initialize(new ChatInboundReceiptRow(), enterprise, clock.instant());
        receipt.setBotBindingId(botId);
        receipt.setConnectionRevision(credentialRevision);
        receipt.setConnectionLeaseVersion(lease == null ? 0L : lease);
        receipt.setPlatformEventId(eventId);
        receipt.setReceiptKey(key);
        receipt.setEventType("card.action.trigger");
        receipt.setPlatformMessageId(context.path("open_message_id").asText());
        receipt.setStatus("ignored");
        receipt.setReceivedAt(clock.instant());
        receipt.setAttemptCount(0);
        receipt.setLeaseVersion(0L);
        receipt.setPayloadEncrypted(cipher.encrypt(enterprise, "receipt", receipt.getId(), Map.of("response", response)));
        receipts.insert(receipt);
        return response;
    }

    public static JsonNode toast(String type, String content) {
        var response = JsonNodeFactory.instance.objectNode();
        response.putObject("toast").put("type", type).put("content", content);
        return response;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "CHAT_CARD_UNAVAILABLE", "此卡片暂时无法操作，请打开群策任务详情修改。");
    }
}
