package com.stonewu.agenteam.model.memory.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemoryWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 50, message = "{validation.textLength}") String memoryKey,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 500, message = "{validation.textLength}") String content,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String sourceMessageId) {
}
