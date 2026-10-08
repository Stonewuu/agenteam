package com.stonewu.agenteam.model.file.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * 客户端的完成声明不能代替服务端保存的实际内容。
 */
public record FileCompleteRequest(
    @JsonProperty(value = "sizeBytes", required = true) @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 20971520L, message = "数值超出允许范围。") long sizeBytes,
    @NotNull(message = "请填写必填项。") @Size(min = 64, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[a-f0-9]{64}$", message = "请选择支持的选项或格式。") String sha256) {
}
