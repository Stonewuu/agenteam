package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CollaborationResultResendRequest(
        @NotBlank(message = "{error.collaboration.collaborationResultResendRequest.requestId.1}") @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "{error.collaboration.collaborationResultResendRequest.requestId.1}") String requestId,
        @NotNull(message = "{error.collaboration.collaborationResultResendRequest.expectedRevision.1}") @Positive(message = "{error.collaboration.collaborationResultResendRequest.expectedRevision.1}") Long expectedRevision,
        @NotNull(message = "{error.collaboration.collaborationResultResendRequest.expectedReplyModeRevision.1}") @PositiveOrZero(message = "{error.collaboration.collaborationResultResendRequest.expectedReplyModeRevision.1}") Long expectedReplyModeRevision) {
}
