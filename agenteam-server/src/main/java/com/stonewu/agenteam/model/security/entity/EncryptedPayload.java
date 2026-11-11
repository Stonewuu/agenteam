package com.stonewu.agenteam.model.security.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 带密钥版本和独立随机数的加密内容，不作为公开响应返回。
 */
public record EncryptedPayload(String keyVersion, String nonce, String ciphertext) {
    @Override
    public String toString() {
        return Messages.log("log.security.encryptedPayload.toString.1");
    }
}
