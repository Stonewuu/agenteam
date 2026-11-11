package com.stonewu.agenteam.model.notification.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReadAllNotificationsRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 19, message = "{validation.textLength}") @Pattern(regexp = "^(0|[1-9][0-9]*)$", message = "{validation.option}") String throughSequence) {
}
