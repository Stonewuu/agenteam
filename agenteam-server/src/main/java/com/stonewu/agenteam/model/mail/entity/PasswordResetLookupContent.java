package com.stonewu.agenteam.model.mail.entity;

/**
 * 找回请求统一排队后再核对账号，避免通过同步处理差异查询账号是否存在。
 */
public record PasswordResetLookupContent(String identifier, String expiresAt) {
    @Override
    public String toString() {
        return "PasswordResetLookupContent[账号信息已隐藏]";
    }
}
