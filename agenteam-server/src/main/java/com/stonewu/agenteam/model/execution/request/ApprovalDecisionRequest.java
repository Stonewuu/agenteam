package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 决定必须携带用户实际查看的操作摘要，不接收替换参数。
 */
public record ApprovalDecisionRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:approve|reject)", message = "请选择支持的选项或格式。") String decision,
    @NotNull(message = "请填写必填项。") @Size(min = 64, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[a-f0-9]{64}$", message = "请选择支持的选项或格式。") String requestHash,
    @Size(min = 0, max = 500, message = "文字长度不符合要求。") String reason) {
}
