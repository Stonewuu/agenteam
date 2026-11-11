package com.stonewu.agenteam.model.file.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 准备上传只声明用途和预期内容，实际文件仍须验证。
 */
public record FilePrepareRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 32, message = "{validation.textLength}") @Pattern(regexp = "(?:attachment|knowledge|data_import|skill_import)", message = "{validation.option}") String purpose,
    @JsonProperty(value = "resourceId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String resourceId,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 255, message = "{validation.textLength}") String name,
    @JsonProperty(value = "sizeBytes", required = true) @Min(value = 1L, message = "{validation.range}") @Max(value = 20971520L, message = "{validation.range}") long sizeBytes,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String mediaType,
    @NotNull(message = "{validation.required}") @Size(min = 64, max = 64, message = "{validation.textLength}") @Pattern(regexp = "^[a-f0-9]{64}$", message = "{validation.option}") String sha256) {
}
