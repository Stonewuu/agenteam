package com.stonewu.agenteam.model.file.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 准备上传只声明用途和预期内容，实际文件仍须验证。
 */
public record FilePrepareRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 32, message = "文字长度不符合要求。") @Pattern(regexp = "(?:attachment|knowledge|data_import|skill_import)", message = "请选择支持的选项或格式。") String purpose,
    @JsonProperty(value = "resourceId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String resourceId,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 255, message = "文字长度不符合要求。") String name,
    @JsonProperty(value = "sizeBytes", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 20971520L, message = "数值超出允许范围。") long sizeBytes,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String mediaType,
    @NotNull(message = "请填写必填项。") @Size(min = 64, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[a-f0-9]{64}$", message = "请选择支持的选项或格式。") String sha256) {
}
