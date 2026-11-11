package com.stonewu.agenteam.model.modelprofile.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 密钥为空值时保留原值，空字符串表示清除。
 */
public record ModelProviderWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 32, message = "{validation.textLength}") @Pattern(regexp = "(?:openai|openai_responses|anthropic|gemini)", message = "{validation.option}") String protocol,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 2048, message = "{validation.textLength}") String baseUrl,
    @Size(min = 0, max = 16384, message = "{validation.textLength}") String apiKey,
    @NotNull(message = "{validation.required}") Boolean enabled) {
}
