package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResourceGrantsRequest(
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 500, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid ResourceGrantSpec> grants) {
}
