package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HireDecisionRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:approve|reject)", message = "{validation.option}") String decision,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String reason) {
}
