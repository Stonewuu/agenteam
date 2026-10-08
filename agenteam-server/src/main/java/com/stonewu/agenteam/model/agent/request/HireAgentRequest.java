package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HireAgentRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String agentId,
    @Size(min = 0, max = 500, message = "文字长度不符合要求。") String note) {
}
