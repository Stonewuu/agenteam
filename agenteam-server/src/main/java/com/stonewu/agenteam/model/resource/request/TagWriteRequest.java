package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TagWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") String name) {
}
