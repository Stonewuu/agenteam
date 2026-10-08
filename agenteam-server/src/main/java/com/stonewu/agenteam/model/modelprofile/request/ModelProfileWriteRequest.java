package com.stonewu.agenteam.model.modelprofile.request;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModelProfileWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String providerId,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String modelName,
    @NotNull(message = "请填写必填项。") @Valid ModelCapabilities capabilities,
    @NotNull(message = "请填写必填项。") Boolean enabled) {
}
