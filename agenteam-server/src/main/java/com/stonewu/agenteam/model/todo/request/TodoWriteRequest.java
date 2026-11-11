package com.stonewu.agenteam.model.todo.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TodoWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 200, message = "{validation.textLength}") String title,
    @NotNull(message = "{validation.required}") @Size(min = 0, max = 5000, message = "{validation.textLength}") String description,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String ownerUserId,
    @JsonProperty(value = "teamId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String teamId,
    @JsonProperty(value = "dueDate", required = true) @Size(min = 10, max = 10, message = "{validation.textLength}") String dueDate,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:normal|high)", message = "{validation.option}") String priority,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:manual|message|workflow)", message = "{validation.option}") String sourceType,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String sourceConversationId,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String sourceMessageId,
    @Size(min = 1, max = 100, message = "{validation.textLength}") String sourceRunId) {
}
