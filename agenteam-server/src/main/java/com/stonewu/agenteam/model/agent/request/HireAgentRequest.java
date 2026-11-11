package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HireAgentRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String agentId,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String note) {
}
