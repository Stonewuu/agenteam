package com.stonewu.agenteam.model.execution.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

/**
 * 后台执行记录；固定配置只含凭据引用，不保存凭据明文。
 */
public record RunRecord(String id, String enterpriseId, String conversationId, String userId,
                        String inputMessageId, String outputMessageId, String agentVersionId,
                        String mode, String status, JsonNode executionConfig, int currentAttemptNo,
                        int maxAttempts, long leaseVersion, boolean hasStepErrors, long lastSequence,
                        Instant startedAt, Instant finishedAt, Instant cancelRequestedAt,
                        Instant nextAttemptAt, String errorCode, String errorMessage, Instant createdAt) {
    public boolean terminal() {
        return Set.of("completed", "failed", "cancelled").contains(status);
    }
}
