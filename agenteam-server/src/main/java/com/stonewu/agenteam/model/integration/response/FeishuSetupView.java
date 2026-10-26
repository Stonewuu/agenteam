package com.stonewu.agenteam.model.integration.response;

import java.time.Instant;

/** 页面只接收可继续操作的状态，不接收平台访问凭据。 */
public record FeishuSetupView(String id, String kind, String name, String stage, String message,
        String managerConnectionId, String connectionId, String botId, boolean canOpen, boolean canRetry,
        boolean canCancel, Instant expiresAt, long revision, Instant updatedAt) {
}
