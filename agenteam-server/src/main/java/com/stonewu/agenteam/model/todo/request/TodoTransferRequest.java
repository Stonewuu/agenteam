package com.stonewu.agenteam.model.todo.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TodoTransferRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String ownerUserId,
    @Size(min = 0, max = 500, message = "{validation.textLength}") String reason) {
}
