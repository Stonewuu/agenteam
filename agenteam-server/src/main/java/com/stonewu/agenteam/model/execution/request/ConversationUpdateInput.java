package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 只修改明确提供的会话字段，省略项保持原值。
 */
public record ConversationUpdateInput(
    @Size(min = 1, max = 100, message = "{validation.textLength}") String title,
    Boolean favorite,
    @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:active|archived)", message = "{validation.option}") String status,
    @Pattern(regexp = "default|auto_approve|full_access", message = "{error.execution.conversationUpdateInput.approvalPolicy.1}") String approvalPolicy) {
}
