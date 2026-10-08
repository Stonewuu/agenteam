package com.stonewu.agenteam.model.todo.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TodoWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 200, message = "文字长度不符合要求。") String title,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5000, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String ownerUserId,
    @JsonProperty(value = "teamId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String teamId,
    @JsonProperty(value = "dueDate", required = true) @Size(min = 10, max = 10, message = "文字长度不符合要求。") String dueDate,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:normal|high)", message = "请选择支持的选项或格式。") String priority,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:manual|message|workflow)", message = "请选择支持的选项或格式。") String sourceType,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String sourceConversationId,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String sourceMessageId,
    @Size(min = 1, max = 100, message = "文字长度不符合要求。") String sourceRunId) {
}
