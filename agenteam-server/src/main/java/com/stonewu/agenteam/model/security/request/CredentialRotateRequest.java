package com.stonewu.agenteam.model.security.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 轮换只替换秘密，不改变凭据用途。
 */
public record CredentialRotateRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 8192, message = "文字长度不符合要求。") String secret) {
    @Override
    public String toString() {
        return "CredentialRotateRequest[凭据内容已隐藏]";
    }
}
