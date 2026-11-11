package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 只有登录的原发起人可以补充要求或选择成果公开范围。 */
public record CollaborationInputRequest(
    @NotBlank(message = "{error.collaboration.collaborationActionRequest.requestId.1}") @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "{error.collaboration.collaborationActionRequest.requestId.2}") String requestId,
    @NotNull(message = "{error.collaboration.collaborationActionRequest.expectedRevision.1}") @Positive(message = "{error.collaboration.collaborationActionRequest.expectedRevision.2}") Long expectedRevision,
    @NotBlank(message = "{error.collaboration.collaborationInputRequest.text.1}") @Size(max = 32000, message = "{error.collaboration.collaborationInputRequest.text.2}") String text,
    @Size(max = 20, message = "{error.collaboration.collaborationInputRequest.messageReferences.1}") List<@NotBlank(message = "{validation.collaboration.collaborationInputRequest.messageReferences.1}") @Size(max = 100, message = "{validation.collaboration.collaborationInputRequest.messageReferences.2}") String> messageReferences,
    @Pattern(regexp = "summary_link|chat_full", message = "{error.collaboration.collaborationInputRequest.outputVisibility.1}") String outputVisibility,
    @Size(max = 20, message = "{error.collaboration.collaborationInputRequest.waivedRoleIds.1}") List<@NotBlank(message = "{validation.collaboration.collaborationInputRequest.waivedRoleIds.1}") @Size(max = 100, message = "{validation.collaboration.collaborationInputRequest.waivedRoleIds.2}") String> waivedRoleIds) {
}
