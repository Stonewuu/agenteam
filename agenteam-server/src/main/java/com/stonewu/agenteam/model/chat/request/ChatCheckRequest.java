package com.stonewu.agenteam.model.chat.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 检查针对明确机器人、配置版本和会话，不接受任意接收地址。 */
public record ChatCheckRequest(@NotNull @Positive Long expectedRevision, @Size(max = 100) String conversationId,
        @Size(max = 40) String type, @Size(max = 100) String senderBindingId, @Size(max = 100) String peerBotId) {
    public ChatCheckRequest(Long expectedRevision, String conversationId, String type, String senderBindingId) {
        this(expectedRevision, conversationId, type, senderBindingId, null);
    }
}
