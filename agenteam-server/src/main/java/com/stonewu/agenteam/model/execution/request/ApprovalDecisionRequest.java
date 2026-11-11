package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 决定必须携带用户实际查看的操作摘要，不接收替换参数。
 */
public record ApprovalDecisionRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:approve|reject)", message = "{validation.option}") String decision,
    @NotNull(message = "{validation.required}") @Size(min = 64, max = 64, message = "{validation.textLength}") @Pattern(regexp = "^[a-f0-9]{64}$", message = "{validation.option}") String requestHash,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String reason) {
}
