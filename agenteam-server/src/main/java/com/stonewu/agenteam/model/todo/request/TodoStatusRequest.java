package com.stonewu.agenteam.model.todo.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TodoStatusRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:pending|in_progress|completed|cancelled)", message = "请选择支持的选项或格式。") String status,
    @Size(min = 0, max = 500, message = "文字长度不符合要求。") String reason) {
}
