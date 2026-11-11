package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationReplyModeService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 发送前按当前选择生成内容；已提交平台的请求保持原内容，不能改写后重试。 */
@Service
public class ChatReplyDeliveryPolicy {
    private final CollaborationAccessService access;
    private final ChatConversationService conversations;
    private final ChatPublicLinks links;
    private final ChatReplyPreferenceService preferences;
    private final ChatBotBindingService bots;

    public ChatReplyDeliveryPolicy(CollaborationAccessService access, ChatConversationService conversations, ChatPublicLinks links,
            ChatReplyPreferenceService preferences, ChatBotBindingService bots) {
        this.access = access;
        this.conversations = conversations;
        this.links = links;
        this.preferences = preferences;
        this.bots = bots;
    }

    public JsonNode prepare(ChatOutboundMessageRow row, JsonNode source) {
        if (row.getTaskId() == null) {
            if (row.getFirstSubmittedAt() == null && source.hasNonNull("replySettingsUrl")
                    && !Set.of("feishu", "wecom_aibot").contains(conversations.require(row.getEnterpriseId(), row.getConversationId()).getPlatformCode())) {
                ObjectNode payload = source.deepCopy();
                payload.putObject("content").put("text", Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.1", source.path("content").path("text").asText(), source.path("replySettingsUrl").asText()));
                return payload;
            }
            return source;
        }
        var task = access.require(row.getEnterpriseId(), row.getTaskId());
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        boolean full = "chat_full".equals(task.getOutputVisibility()) && ChatReplyPolicy.allowsFullResult(task, group);
        boolean sensitive = Set.of("completed", "waiting_input", "completed_file", "user_question_text", "user_question_card").contains(row.getKind());
        if (row.getFirstSubmittedAt() != null) {
            if ("reply_settings_update".equals(row.getKind()) && source.path("replyModeRevision").asLong(-1) != CollaborationReplyModeService.revision(task)) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_SKIP", Messages.text("error.chat.chatReplyDeliveryPolicy.prepare.1"));
            }
            if (sensitive && !full && source.path("replyFull").asBoolean(true)) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_UNKNOWN", Messages.text("error.chat.chatReplyDeliveryPolicy.prepare.2"));
            }
            return source;
        }
        if (!full && sensitive && (row.getPartNo() > 0 || "file".equals(source.path("messageType").asText()))) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_SKIP", Messages.text("error.chat.chatReplyDeliveryPolicy.prepare.3"));
        }
        if (!full && "user_question_card".equals(row.getKind()) && "wecom_aibot".equals(group.getPlatformCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_SKIP", Messages.text("error.chat.chatReplyDeliveryPolicy.prepare.4"));
        }
        ObjectNode payload = source.deepCopy();
        payload.put("replyModeRevision", CollaborationReplyModeService.revision(task)).put("replyGroupRevision", group.getRevision())
            .put("replyFull", full);
        boolean wecomConversationCard = "wecom_aibot".equals(group.getPlatformCode())
            && Set.of("accepted", "input_added").contains(row.getKind());
        if (("accepted".equals(row.getKind()) || "reply_settings_update".equals(row.getKind()) || wecomConversationCard) && row.getPartNo() == 0) {
            payload.put("replySettingsUrl", links.task(task.getEnterpriseId(), task.getId()) + "?replyMode=edit");
            if (wecomConversationCard) {
                payload.put("replyNoticeTitle", Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.2"));
                payload.put("conversationCardId", row.getId());
                if (!"TOOL_RESULT_UNKNOWN".equals(task.getErrorCode())) {
                    payload.putObject("content").put("text", full ? Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.3") : Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.4"));
                }
            }
        }
        if (sensitive && !full) {
            String notice = Set.of("waiting_input", "user_question_text", "user_question_card").contains(row.getKind())
                ? Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.5") : Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.6");
            payload.remove("userQuestion");
            payload.remove("questionCardId");
            String address = links.task(task.getEnterpriseId(), task.getId());
            payload.put("messageType", "text").putObject("content").put("text", notice + "\n"
                + (Set.of("feishu", "wecom_aibot").contains(group.getPlatformCode()) ? Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.7", address) : address));
        }
        if (payload.hasNonNull("replySettingsUrl") && "feishu".equals(group.getPlatformCode())) {
            var preference = preferences.find(task.getEnterpriseId(), task.getInitiatorUserId(), task.getConversationId());
            payload.put("replyCallbackEnabled", bots.capability(bots.require(row.getEnterpriseId(), row.getBotBindingId()), "cardInteraction"))
                .put("replyDefaultRevision", preference == null ? 0 : preference.getRevision())
                .put("replyFullAllowed", ChatReplyPolicy.allowsFullResult(task, group)).put("replyDirect", "direct".equals(group.getConversationKind()));
        }
        if (!Set.of("feishu", "wecom_aibot").contains(group.getPlatformCode()) && payload.hasNonNull("replySettingsUrl")) {
            payload.putObject("content").put("text", Messages.text("text.chat.chatReplyDeliveryPolicy.prepare.1", source.path("content").path("text").asText(), payload.path("replySettingsUrl").asText()));
        }
        if ("wecom_aibot".equals(group.getPlatformCode())) {
            payload.put("chatType", "direct".equals(group.getConversationKind()) ? 1 : 2);
        }
        return payload;
    }

    public void verify(ChatOutboundMessageRow row, JsonNode payload) {
        if (row.getTaskId() == null) {
            return;
        }
        var task = access.require(row.getEnterpriseId(), row.getTaskId());
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        if (row.getFirstSubmittedAt() == null && (payload.path("replyModeRevision").asLong(-1) != CollaborationReplyModeService.revision(task)
                || payload.path("replyGroupRevision").asLong(-1) != group.getRevision()
                || payload.path("replyFull").asBoolean() != ("chat_full".equals(task.getOutputVisibility()) && ChatReplyPolicy.allowsFullResult(task, group)))) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_CHANGED", Messages.text("error.chat.chatReplyDeliveryPolicy.verify.1"));
        }
        prepare(row, payload);
    }
}
