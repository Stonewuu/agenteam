package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RevokeVersionRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 500, message = "文字长度不符合要求。") String reason) {
}
