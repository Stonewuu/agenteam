package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record CollaborationReplyModeRequest(
        @NotNull(message = "{error.chat.chatReplyPreferenceRequest.outputVisibility.1}") @Pattern(regexp = "chat_full|summary_link", message = "{error.chat.chatReplyPreferenceRequest.outputVisibility.2}") String outputVisibility,
        @NotNull(message = "{error.chat.chatReplyPreferenceRequest.expectedRevision.1}") @PositiveOrZero(message = "{error.chat.chatReplyPreferenceRequest.expectedRevision.1}") Long expectedRevision,
        boolean saveAsDefault,
        @PositiveOrZero(message = "{error.chat.chatReplyPreferenceRequest.expectedRevision.1}") Long expectedDefaultRevision) {
}
