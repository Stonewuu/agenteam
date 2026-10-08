package com.stonewu.agenteam.model.execution.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 第一次发送时选择员工，会话版本由服务端固定。
 */
public record NewConversationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String agentId,
    @NotNull(message = "请填写必填项。") @Valid MessageInput input,
    @Pattern(regexp = "default|auto_approve|full_access", message = "请选择有效的工具审批策略。") String approvalPolicy,
    @Size(min = 1, max = 100, message = "请选择有效的项目。") String projectId) {
    public NewConversationInput(String agentId, MessageInput input, String approvalPolicy) {
        this(agentId, input, approvalPolicy, null);
    }

    public NewConversationInput(String agentId, MessageInput input) {
        this(agentId, input, null, null);
    }
}
