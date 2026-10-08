package com.stonewu.agenteam.model.integration.entity;

/**
 * 平台接受、明确拒绝和结果未知分别处理，接受不代表用户已读。
 */
public record ChannelSendResult(Outcome outcome, String providerCode, String messageId,
                               String errorCode, String summary, Long retryAfterSeconds,
                               Integer httpStatus, String traceId) {
    public enum Outcome {
        ACCEPTED, RETRYABLE_FAILURE, PERMANENT_FAILURE, TOKEN_EXPIRED, UNKNOWN
    }
}
