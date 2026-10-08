package com.stonewu.agenteam.model.notification.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReadAllNotificationsRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 19, message = "文字长度不符合要求。") @Pattern(regexp = "^(0|[1-9][0-9]*)$", message = "请选择支持的选项或格式。") String throughSequence) {
}
