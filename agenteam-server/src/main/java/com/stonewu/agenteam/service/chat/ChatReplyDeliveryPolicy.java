package com.stonewu.agenteam.service.chat;

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
                    && !"feishu".equals(conversations.require(row.getEnterpriseId(), row.getConversationId()).getPlatformCode())) {
                ObjectNode payload = source.deepCopy();
                payload.putObject("content").put("text", source.path("content").path("text").asText()
                    + "\n回复方式：" + source.path("replySettingsUrl").asText());
                return payload;
            }
            return source;
        }
        var task = access.require(row.getEnterpriseId(), row.getTaskId());
        var group = conversations.require(row.getEnterpriseId(), row.getConversationId());
        boolean full = "chat_full".equals(task.getOutputVisibility()) && ChatReplyPolicy.allowsFullResult(task, group);
        boolean sensitive = Set.of("completed", "waiting_input", "completed_file").contains(row.getKind());
        if (row.getFirstSubmittedAt() != null) {
            if ("reply_settings_update".equals(row.getKind()) && source.path("replyModeRevision").asLong(-1) != CollaborationReplyModeService.revision(task)) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_SKIP", "已有更新的回复设置，旧卡片不再重复更新。");
            }
            if (sensitive && !full && source.path("replyFull").asBoolean(true)) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_UNKNOWN", "原请求可能已经发送，已停止再次发送其中的内容。");
            }
            return source;
        }
        if (!full && sensitive && (row.getPartNo() > 0 || "file".equals(source.path("messageType").asText()))) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_SKIP", "此任务仅发送链接。");
        }
        ObjectNode payload = source.deepCopy();
        payload.put("replyModeRevision", CollaborationReplyModeService.revision(task)).put("replyGroupRevision", group.getRevision())
            .put("replyFull", full);
        if (("accepted".equals(row.getKind()) || "reply_settings_update".equals(row.getKind())) && row.getPartNo() == 0) {
            payload.put("replySettingsUrl", links.task(task.getEnterpriseId(), task.getId()) + "?replyMode=edit");
        }
        if (sensitive && !full) {
            String notice = "waiting_input".equals(row.getKind()) ? "有问题需要你补充，请打开任务查看并回复。" : "本次回复已完成，请打开任务查看。";
            String address = links.task(task.getEnterpriseId(), task.getId());
            payload.put("messageType", "text").putObject("content").put("text", notice + "\n"
                + ("feishu".equals(group.getPlatformCode()) ? "[打开任务](" + address + ")" : address));
        }
        if (payload.hasNonNull("replySettingsUrl") && "feishu".equals(group.getPlatformCode())) {
            var preference = preferences.find(task.getEnterpriseId(), task.getInitiatorUserId(), task.getConversationId());
            payload.put("replyCallbackEnabled", bots.capability(bots.require(row.getEnterpriseId(), row.getBotBindingId()), "cardInteraction"))
                .put("replyDefaultRevision", preference == null ? 0 : preference.getRevision())
                .put("replyFullAllowed", ChatReplyPolicy.allowsFullResult(task, group)).put("replyDirect", "direct".equals(group.getConversationKind()));
        }
        if (!"feishu".equals(group.getPlatformCode()) && payload.hasNonNull("replySettingsUrl")) {
            payload.putObject("content").put("text", source.path("content").path("text").asText()
                + "\n回复方式：" + payload.path("replySettingsUrl").asText());
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
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_REPLY_MODE_CHANGED", "回复方式已更新，正在按当前设置准备消息。");
        }
        prepare(row, payload);
    }
}
