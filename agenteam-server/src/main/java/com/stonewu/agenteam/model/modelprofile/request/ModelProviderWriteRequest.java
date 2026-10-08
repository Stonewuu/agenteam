package com.stonewu.agenteam.model.modelprofile.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 密钥为空值时保留原值，空字符串表示清除。
 */
public record ModelProviderWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 32, message = "文字长度不符合要求。") @Pattern(regexp = "(?:openai)", message = "请选择支持的选项或格式。") String protocol,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 2048, message = "文字长度不符合要求。") String baseUrl,
    @Size(min = 0, max = 16384, message = "文字长度不符合要求。") String apiKey,
    @NotNull(message = "请填写必填项。") Boolean enabled) {
}
