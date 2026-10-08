package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HireStatusRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:active|paused|terminated)", message = "请选择支持的选项或格式。") String status) {
}
