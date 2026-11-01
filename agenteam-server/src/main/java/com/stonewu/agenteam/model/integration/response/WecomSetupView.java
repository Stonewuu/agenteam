package com.stonewu.agenteam.model.integration.response;

import java.time.Instant;

/** 创建进度不向浏览器返回机器人密钥和平台会话码。 */
public record WecomSetupView(String id, String name, String platformName, String stage, String message,
        String enterpriseConnectionId, String connectionId, String botId, boolean canOpen,
        boolean canRetry, boolean canCancel, Instant expiresAt, long revision, Instant updatedAt) {
}
