package com.stonewu.agenteam.model.security.entity;

/**
 * 带密钥版本和独立随机数的加密内容，不作为公开响应返回。
 */
public record EncryptedPayload(String keyVersion, String nonce, String ciphertext) {
    @Override
    public String toString() {
        return "EncryptedPayload[加密内容已隐藏]";
    }
}
