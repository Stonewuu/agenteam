package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

/** 仅接受当前机器人在原会话发出的提问卡片，不接受转发卡片或他人代答。 */
@Service
public class WecomQuestionCardService {
    private final ChatOutboundMessageMapper messages;
    private final ChatConversationService conversations;
    private final ChatBotBindingService bots;
    private final ChatIdentityService identities;
    private final ChatPayloadCipher cipher;
    private final ChatQuestionReplyService replies;
    private final ChatOutboundService outbound;
    private final ChatSessionSelectionService sessions;

    public WecomQuestionCardService(ChatOutboundMessageMapper messages, ChatConversationService conversations,
            ChatBotBindingService bots, ChatIdentityService identities, ChatPayloadCipher cipher,
            ChatQuestionReplyService replies, ChatOutboundService outbound, ChatSessionSelectionService sessions) {
        this.messages = messages;
        this.conversations = conversations;
        this.bots = bots;
        this.identities = identities;
        this.cipher = cipher;
        this.replies = replies;
        this.outbound = outbound;
        this.sessions = sessions;
    }

    private Target target(ChatBotBindingRow bot, ChatInboundReceiptRow receipt, NormalizedChatEvent event) {
        String action = event.metadata().path("event_key").asText();
        if (!"wecom_aibot".equals(event.providerCode()) || !"user".equals(event.senderKind()) || !action.startsWith("question_")) {
            return null;
        }
        var source = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, bot.getEnterpriseId()).eq(ChatOutboundMessageRow::getBotBindingId, bot.getId())
            .eq(ChatOutboundMessageRow::getId, event.metadata().path("task_id").asText())
            .eq(ChatOutboundMessageRow::getKind, "user_question_card").eq(ChatOutboundMessageRow::getStatus, "sent"));
        if (source == null || source.getRenderedPayloadEncrypted() == null) {
            return null;
        }
        var group = conversations.require(bot.getEnterpriseId(), source.getConversationId());
        var member = conversations.member(bot.getEnterpriseId(), group.getId(), bot.getId());
        var app = bots.application(bot);
        var payload = cipher.decrypt(bot.getEnterpriseId(), "outbound-rendered", source.getId(), source.getRenderedPayloadEncrypted());
        if (!"enabled".equals(group.getStatus()) || !Boolean.TRUE.equals(bot.getChatEnabled()) || member == null
                || !"present".equals(member.getMembershipStatus()) || !Boolean.TRUE.equals(member.getReceiveEnabled())
                || !Boolean.TRUE.equals(member.getSendEnabled()) || !member.getExternalChatId().equals(event.externalChatId())
                || !group.getConversationKind().equals(event.chatKind()) || receipt.getConnectionRevision() != app.credentialRevision()
                || payload.path("credentialRevision").asLong(-1) != app.credentialRevision()
                || !bots.currentCredentials(bot, app.credentialRevision())) {
            return null;
        }
        var binding = identities.find(bot.getEnterpriseId(), bot.getConnectionId(), event.subjectType(), event.senderId());
        if (binding.isEmpty()) {
            return null;
        }
        var actor = identities.actor(bot.getEnterpriseId(), binding.get().getUserId(), binding.get().getId());
        return new Target(source, group, payload, actor);
    }

    public boolean accept(ChatBotBindingRow bot, ChatInboundReceiptRow receipt, NormalizedChatEvent event) {
        var target = target(bot, receipt, event);
        if (target == null) {
            return false;
        }
        var source = target.source();
        var group = target.group();
        var payload = target.payload();
        var actor = target.actor();
        String action = event.metadata().path("event_key").asText();
        var answer = replies.card(actor, source, payload, action, null);
        if (answer == null) {
            sessions.select(actor, group.getId(), bot.getId(), source.getTaskId(), receipt.getReceivedAt());
        }
        String notice = answer == null ? ("group".equals(group.getConversationKind()) ? "请 @ 我输入这个问题的回答。" : "请输入这个问题的回答。")
            : "已收到你的回答。";
        outbound.text(bot.getEnterpriseId(), group.getId(), bot.getId(), source.getTaskId(), null,
            "question-reply:" + (answer == null ? receipt.getId() : answer.id()), "question_answered", notice, null);
        return true;
    }

    public void rejected(ChatBotBindingRow bot, ChatInboundReceiptRow receipt, NormalizedChatEvent event, String code) {
        var target = target(bot, receipt, event);
        if (target == null) {
            return;
        }
        try {
            replies.requireCardQuestion(target.actor(), target.source(), target.payload());
        } catch (ApiException unavailable) {
            return;
        }
        String notice = "QUESTION_ALREADY_ANSWERED".equals(code) ? "这个问题已经回答，无需再次选择。" : "这个问题已结束，请继续当前对话。";
        // 提示属于当前已核验会话，不依赖已经取消或结束的任务仍允许发送。
        outbound.text(bot.getEnterpriseId(), target.group().getId(), bot.getId(), null, null,
            "question-rejected:" + receipt.getId(), "question_rejected", notice, null);
    }

    private record Target(ChatOutboundMessageRow source, ChatConversationRow group, JsonNode payload, AuthContext actor) {
    }
}
