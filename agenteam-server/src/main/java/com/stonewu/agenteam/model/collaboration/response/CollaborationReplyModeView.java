package com.stonewu.agenteam.model.collaboration.response;

import com.stonewu.agenteam.model.chat.response.ChatReplyPreferenceView;

public record CollaborationReplyModeView(String taskId, long revision, String outputVisibility,
        ChatReplyPreferenceView conversation, boolean canSendResult) {
}
