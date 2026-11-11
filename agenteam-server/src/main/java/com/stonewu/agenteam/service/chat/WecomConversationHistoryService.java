package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/** 在当前群内按需展示本人与当前机器人的会话，卡片保存当次可选项，按钮不接受任意任务编号。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WecomConversationHistoryService {
    private static final int PAGE_SIZE = 3;
    private final CollaborationTaskMapper tasks;
    private final ChatOutboundMessageMapper messages;
    private final ChatOutboundService outbound;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ChatSessionSelectionService sessions;
    private final CollaborationAccessService access;

    public WecomConversationHistoryService(CollaborationTaskMapper tasks, ChatOutboundMessageMapper messages,
            ChatOutboundService outbound, ChatPayloadCipher cipher, ResourceJson json,
            ChatSessionSelectionService sessions, CollaborationAccessService access) {
        this.tasks = tasks;
        this.messages = messages;
        this.outbound = outbound;
        this.cipher = cipher;
        this.json = json;
        this.sessions = sessions;
        this.access = access;
    }

    public void show(AuthContext actor, ChatConversationRow group, ChatBotBindingRow bot, String requestId, int page, Instant before) {
        var result = tasks.selectPage(new Page<CollaborationTaskRow>(page, PAGE_SIZE), scope(actor, group.getId(), bot.getId())
            .le(CollaborationTaskRow::getCreatedAt, before).notIn(CollaborationTaskRow::getStatus, "cancelled", "cancelling", "failed")
            .orderByDesc(CollaborationTaskRow::getCreatedAt, CollaborationTaskRow::getId));
        String key = "conversation-history:" + requestId;
        if (result.getRecords().isEmpty()) {
            outbound.text(actor.enterpriseId(), group.getId(), bot.getId(), null, null, key, "conversation_history_empty",
                page == 1 ? Messages.text("text.chat.wecomConversationHistoryService.show.1") : Messages.text("text.chat.wecomConversationHistoryService.show.2"), null);
            return;
        }
        var row = outbound.text(actor.enterpriseId(), group.getId(), bot.getId(), null, null, key, "conversation_history", Messages.text("text.chat.wecomConversationHistoryService.show.3"), null);
        if (row.getFirstSubmittedAt() != null) {
            return;
        }
        var payload = (ObjectNode) cipher.decrypt(actor.enterpriseId(), "outbound", row.getId(), row.getPayloadEncrypted());
        payload.put("conversationCardId", row.getId()).put("historyUserId", actor.userId()).put("historyPage", page)
            .put("historyBefore", before.toString()).put("historyHasNext", result.hasNext()).put("historyListLayout", true)
            .put("chatType", "direct".equals(group.getConversationKind()) ? 1 : 2);
        var choices = payload.putArray("historyChoices");
        for (var task : result.getRecords()) {
            var objective = cipher.decrypt(actor.enterpriseId(), "task-objective", task.getId(), task.getObjectiveEncrypted());
            String title = WecomConversationCommands.withoutOwnMention(objective.path("text").asText(), bot.getRoleName())
                .replaceAll("\\s+", " ").strip();
            if (title.isBlank()) {
                title = Messages.text("text.chat.wecomConversationHistoryService.show.4");
            }
            if (title.codePointCount(0, title.length()) > 24) {
                title = title.substring(0, title.offsetByCodePoints(0, 24)) + "…";
            }
            String choiceKey = "history_choose_" + choices.size();
            String label = Messages.text("text.chat.wecomConversationHistoryService.show.5", (choices.size() + 1));
            choices.addObject().put("key", choiceKey).put("taskId", task.getId())
                .put("title", title).put("label", label);
        }
        if (messages.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatOutboundMessageRow::getId, row.getId()).isNull(ChatOutboundMessageRow::getFirstSubmittedAt)
            .set(ChatOutboundMessageRow::getPayloadEncrypted, cipher.encrypt(actor.enterpriseId(), "outbound", row.getId(), payload))
            .set(ChatOutboundMessageRow::getPayloadHash, json.hash(payload))) != 1) {
            throw new IllegalStateException(Messages.text("error.chat.wecomConversationHistoryService.show.1"));
        }
    }

    public boolean accept(AuthContext actor, ChatConversationRow group, ChatBotBindingRow bot, JsonNode payload, String action, String receiptId, Instant received) {
        if (!actor.userId().equals(payload.path("historyUserId").asText())) {
            return false;
        }
        int page = payload.path("historyPage").asInt();
        if ("history_next".equals(action) && payload.path("historyHasNext").asBoolean()
                || "history_previous".equals(action) && page > 1) {
            show(actor, group, bot, receiptId, "history_next".equals(action) ? page + 1 : page - 1,
                Instant.parse(payload.path("historyBefore").asText()));
            return true;
        }
        for (var choice : payload.path("historyChoices")) {
            if (!action.equals(choice.path("key").asText())) {
                continue;
            }
            var task = tasks.selectOne(scope(actor, group.getId(), bot.getId()).eq(CollaborationTaskRow::getId, choice.path("taskId").asText()));
            if (task == null || Set.of("cancelled", "cancelling", "failed").contains(task.getStatus())) {
                return false;
            }
            access.owned(actor, task.getId());
            sessions.select(actor, group.getId(), bot.getId(), task.getId(), received);
            outbound.text(actor.enterpriseId(), group.getId(), bot.getId(), null, null, "conversation-card:" + receiptId, "conversation_selected",
                Messages.text("text.chat.wecomConversationHistoryService.accept.1", ("group".equals(group.getConversationKind()) ? Messages.text("text.chat.chatRoutingService.route.7") : Messages.text("text.chat.chatRoutingService.route.6"))), null);
            return true;
        }
        return false;
    }

    private LambdaQueryWrapper<CollaborationTaskRow> scope(AuthContext actor, String group, String bot) {
        return new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getConversationId, group).eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
            .eq(CollaborationTaskRow::getEntryBotId, bot);
    }

}
