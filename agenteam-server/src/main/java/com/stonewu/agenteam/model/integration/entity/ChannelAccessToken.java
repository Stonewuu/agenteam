package com.stonewu.agenteam.model.integration.entity;

/**
 * 平台实际返回的短期访问凭证和剩余有效秒数。
 */
public record ChannelAccessToken(String value, long expiresInSeconds) {
    @Override
    public String toString() {
        return "ChannelAccessToken[凭证已隐藏, expiresInSeconds=" + expiresInSeconds + "]";
    }
}
