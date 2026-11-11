package com.stonewu.agenteam.model.todo.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TodoStatusRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:pending|in_progress|completed|cancelled)", message = "{validation.option}") String status,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String reason) {
}
