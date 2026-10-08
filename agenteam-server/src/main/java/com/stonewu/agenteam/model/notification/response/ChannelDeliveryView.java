package com.stonewu.agenteam.model.notification.response;

/** 发送结果不包含通知正文、外部成员编号、请求内容或应用凭据。 */
public record ChannelDeliveryView(String id, String notificationId, String recipientName, String connectionId,
                                   String connectionName, String providerName, String status, int attemptCount,
                                   int manualRetryCount, String nextAttemptAt, String expiresAt, String acceptedAt,
                                   String errorSummary, boolean canRetry, boolean duplicateConfirmationRequired,
                                   String revision, String createdAt) {
}
