package com.stonewu.agenteam.model.security.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 秘密只接收新值，调试字符串也不返回秘密内容。
 */
public record CredentialWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:bearer|basic|database|api_key)", message = "请选择支持的选项或格式。") String kind,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 8192, message = "文字长度不符合要求。") String secret) {
    @Override
    public String toString() {
        return "CredentialWriteRequest[凭据内容已隐藏]";
    }
}
