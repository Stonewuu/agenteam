package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 第一次发送时选择员工，会话版本由服务端固定。
 */
public record NewConversationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String agentId,
    @NotNull(message = "{validation.required}") @Valid MessageInput input,
    @Pattern(regexp = "default|auto_approve|full_access", message = "{error.execution.conversationUpdateInput.approvalPolicy.1}") String approvalPolicy,
    @Size(min = 1, max = 100, message = "{error.execution.newConversationInput.projectId.1}") String projectId) {
    public NewConversationInput(String agentId, MessageInput input, String approvalPolicy) {
        this(agentId, input, approvalPolicy, null);
    }

    public NewConversationInput(String agentId, MessageInput input) {
        this(agentId, input, null, null);
    }
}
