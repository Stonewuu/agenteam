package com.stonewu.agenteam.model.enterprise.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemberRemovalRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 8192, message = "{validation.textLength}") String impactToken,
    @JsonProperty(value = "resourceOwnerId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String resourceOwnerId,
    @JsonProperty(value = "todoOwnerId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String todoOwnerId,
    @JsonProperty(value = "cancelOpenTodos", required = true) boolean cancelOpenTodos) {
}
