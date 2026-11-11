package com.stonewu.agenteam.model.integration.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 平台实际返回的短期访问凭证和剩余有效秒数。
 */
public record ChannelAccessToken(String value, long expiresInSeconds) {
    @Override
    public String toString() {
        return Messages.log("log.integration.channelAccessToken.toString.1", expiresInSeconds);
    }
}
