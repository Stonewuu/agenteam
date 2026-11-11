package com.stonewu.agenteam.model.enterprise.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ActiveStatusPayload(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:active|disabled)", message = "{validation.option}") String status) {
}
