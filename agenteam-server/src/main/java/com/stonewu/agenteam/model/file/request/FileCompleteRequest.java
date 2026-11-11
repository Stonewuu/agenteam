package com.stonewu.agenteam.model.file.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 客户端的完成声明不能代替服务端保存的实际内容。
 */
public record FileCompleteRequest(
    @JsonProperty(value = "sizeBytes", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 20971520L, message = "{validation.range}") long sizeBytes,
    @NotNull(message = "{validation.required}") @Size(min = 64, max = 64, message = "{validation.textLength}") @Pattern(regexp = "^[a-f0-9]{64}$", message = "{validation.option}") String sha256) {
}
