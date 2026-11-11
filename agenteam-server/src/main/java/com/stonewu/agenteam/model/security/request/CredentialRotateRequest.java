package com.stonewu.agenteam.model.security.request;

import com.stonewu.agenteam.service.i18n.Messages;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 轮换只替换秘密，不改变凭据用途。
 */
public record CredentialRotateRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 8192, message = "{validation.textLength}") String secret) {
    @Override
    public String toString() {
        return Messages.log("log.security.credentialRotateRequest.toString.1");
    }
}
