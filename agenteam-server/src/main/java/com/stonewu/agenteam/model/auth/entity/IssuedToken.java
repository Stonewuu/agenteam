package com.stonewu.agenteam.model.auth.entity;

/**
 * 新生成的单次凭据，原始值只能交给短期加密邮件内容。
 */
public record IssuedToken(String id, String value, String hash) {
    @Override
    public String toString() {
        return "IssuedToken[凭据已隐藏]";
    }
}
