package com.stonewu.agenteam.model.security.request;

import com.stonewu.agenteam.service.i18n.Messages;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 秘密只接收新值，调试字符串也不返回秘密内容。
 */
public record CredentialWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:bearer|basic|database|api_key)", message = "{validation.option}") String kind,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 8192, message = "{validation.textLength}") String secret) {
    @Override
    public String toString() {
        return Messages.log("log.security.credentialWriteRequest.toString.1");
    }
}
