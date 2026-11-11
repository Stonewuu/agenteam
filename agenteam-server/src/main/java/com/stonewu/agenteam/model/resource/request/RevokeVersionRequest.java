package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RevokeVersionRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 500, message = "{validation.textLength}") String reason) {
}
