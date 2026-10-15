package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;

/** 私聊仅向任务原发起人交付；群聊继续遵守管理员设置的成果公开范围。 */
public final class ChatReplyPolicy {
    private ChatReplyPolicy() {
    }

    public static String defaultOutputVisibility(ChatConversationRow conversation) {
        return "direct".equals(conversation.getConversationKind()) || "full_allowed".equals(conversation.getResultPolicy())
            ? "chat_full" : "summary_link";
    }

    public static boolean recipientMatches(CollaborationTaskRow task, ChatConversationRow conversation) {
        return task.getEnterpriseId().equals(conversation.getEnterpriseId())
            && task.getConversationId().equals(conversation.getId())
            && (!"direct".equals(conversation.getConversationKind())
                || task.getOriginUserBindingId() != null && task.getOriginUserBindingId().equals(conversation.getDirectBindingId()));
    }

    public static boolean allowsFullResult(CollaborationTaskRow task, ChatConversationRow conversation) {
        return recipientMatches(task, conversation)
            && ("direct".equals(conversation.getConversationKind()) || "full_allowed".equals(conversation.getResultPolicy()));
    }
}
