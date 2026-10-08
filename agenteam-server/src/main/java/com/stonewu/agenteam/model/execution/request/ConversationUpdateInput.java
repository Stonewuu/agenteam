package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 只修改明确提供的会话字段，省略项保持原值。
 */
public record ConversationUpdateInput(
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String title,
    Boolean favorite,
    @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:active|archived)", message = "请选择支持的选项或格式。") String status,
    @Pattern(regexp = "default|auto_approve|full_access", message = "请选择有效的工具审批策略。") String approvalPolicy) {
}
