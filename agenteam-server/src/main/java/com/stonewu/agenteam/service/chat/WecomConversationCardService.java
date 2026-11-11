package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 卡片只能选择本人的原会话；转发卡片、其他企业和已更换凭据的卡片不能修改对话。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WecomConversationCardService {
    private final ChatOutboundMessageMapper messages;
    private final ChatConversationService conversations;
    private final ChatIdentityService identities;
    private final ChatBotBindingService bots;
    private final ChatPayloadCipher cipher;
    private final CollaborationAccessService access;
    private final ChatSessionSelectionService sessions;
    private final ChatOutboundService outbound;
    private final WecomConversationHistoryService history;

    public WecomConversationCardService(ChatOutboundMessageMapper messages, ChatConversationService conversations,
            ChatIdentityService identities, ChatBotBindingService bots, ChatPayloadCipher cipher,
            CollaborationAccessService access, ChatSessionSelectionService sessions, ChatOutboundService outbound,
            WecomConversationHistoryService history) {
        this.messages = messages;
        this.conversations = conversations;
        this.identities = identities;
        this.bots = bots;
        this.cipher = cipher;
        this.access = access;
        this.sessions = sessions;
        this.outbound = outbound;
        this.history = history;
    }

    public boolean accept(ChatBotBindingRow bot, ChatInboundReceiptRow receipt, NormalizedChatEvent event) {
        String action = event.metadata().path("event_key").asText();
        if (!"wecom_aibot".equals(event.providerCode()) || !"user".equals(event.senderKind())
            || !Set.of("conversation_new", "conversation_resume", "history_next", "history_previous",
                "history_choose_0", "history_choose_1", "history_choose_2", "history_choose_3").contains(action)) {
            return false;
        }
        var source = messages.selectOne(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, bot.getEnterpriseId())
            .eq(ChatOutboundMessageRow::getBotBindingId, bot.getId())
            .eq(ChatOutboundMessageRow::getId, event.metadata().path("task_id").asText())
            .in(ChatOutboundMessageRow::getKind, "accepted", "input_added", "completed", "waiting_input", "conversation_history")
            .eq(ChatOutboundMessageRow::getStatus, "sent"));
        if (source == null || source.getRenderedPayloadEncrypted() == null) {
            return false;
        }
        var group = conversations.require(bot.getEnterpriseId(), source.getConversationId());
        var member = conversations.member(bot.getEnterpriseId(), group.getId(), bot.getId());
        var payload = cipher.decrypt(bot.getEnterpriseId(), "outbound-rendered", source.getId(), source.getRenderedPayloadEncrypted());
        var app = bots.application(bot);
        if (!"enabled".equals(group.getStatus()) || !Boolean.TRUE.equals(bot.getChatEnabled())
            || !group.getConversationKind().equals(event.chatKind()) || member == null
            || !"present".equals(member.getMembershipStatus()) || !Boolean.TRUE.equals(member.getReceiveEnabled())
            || !Boolean.TRUE.equals(member.getSendEnabled()) || !member.getExternalChatId().equals(event.externalChatId())
            || !source.getId().equals(payload.path("conversationCardId").asText())
            || receipt.getConnectionRevision() != app.credentialRevision()
            || payload.path("credentialRevision").asLong(-1) != app.credentialRevision()
            || !bots.currentCredentials(bot, app.credentialRevision())) {
            return false;
        }
        var binding = identities.find(bot.getEnterpriseId(), bot.getConnectionId(), event.subjectType(), event.senderId());
        if (binding.isEmpty()) {
            return false;
        }
        var actor = identities.actor(bot.getEnterpriseId(), binding.get().getUserId(), binding.get().getId());
        if ("conversation_history".equals(source.getKind())) {
            return history.accept(actor, group, bot, payload, action, receipt.getId(), receipt.getReceivedAt());
        }
        if (source.getTaskId() == null || !Set.of("conversation_new", "conversation_resume").contains(action)) {
            return false;
        }
        var task = access.require(bot.getEnterpriseId(), source.getTaskId());
        if (!actor.userId().equals(task.getInitiatorUserId()) || !bot.getId().equals(task.getEntryBotId())) {
            return false;
        }
        access.owned(actor, task.getId());
        boolean startNew = "conversation_new".equals(action);
        if (!startNew && Set.of("cancelled", "cancelling").contains(task.getStatus())) {
            return false;
        }
        sessions.select(actor, group.getId(), bot.getId(), startNew ? null : task.getId(), receipt.getReceivedAt());
        String next = "group".equals(group.getConversationKind()) ? Messages.text("text.chat.wecomConversationCardService.accept.1") : Messages.text("text.chat.wecomConversationCardService.accept.2");
        outbound.text(bot.getEnterpriseId(), group.getId(), bot.getId(), null, null,
            "conversation-card:" + receipt.getId(), "conversation_selected",
            startNew ? Messages.text("text.chat.wecomConversationCardService.accept.3", next) : Messages.text("text.chat.wecomConversationCardService.accept.4", next), null);
        return true;
    }
}
