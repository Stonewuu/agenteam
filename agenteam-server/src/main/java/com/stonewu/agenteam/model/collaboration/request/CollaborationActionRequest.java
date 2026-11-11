package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 取消和重试请求使用原记录版本，重试时必须明确具体工作。 */
public record CollaborationActionRequest(@NotBlank(message = "{error.collaboration.collaborationActionRequest.requestId.1}")
        @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "{error.collaboration.collaborationActionRequest.requestId.2}") String requestId,
        @NotNull(message = "{error.collaboration.collaborationActionRequest.expectedRevision.1}") @Positive(message = "{error.collaboration.collaborationActionRequest.expectedRevision.2}") Long expectedRevision,
        @Size(max = 100, message = "{error.collaboration.collaborationActionRequest.workItemId.1}") String workItemId, @Size(max = 2000, message = "{error.collaboration.collaborationActionRequest.reason.1}") String reason) {
}
