package com.stonewu.agenteam.model.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;

import java.time.Instant;

/**
 * 原始参数与框架结果只在执行服务解密，接口不得直接返回本记录。
 */
public record ToolCallRecord(String id, String enterpriseId, String runId, String stepId, String actorUserId,
                             String resourceId, String resourceKind, String resourceVersionId, Long draftRevision,
                             String pluginToolId, String toolName, String operationId,
                             String frameworkCallId, String frameworkSessionId, String operationClass, String status,
                             String argumentHash, String requestHash, EncryptedPayload requestEncrypted,
                             EncryptedPayload resultEncrypted, JsonNode requestRedacted, JsonNode resultRedacted,
                             long leaseVersion, int attemptCount, int queryCount, Instant lastQueryAt,
                             Instant submittedAt, Instant startedAt,
                             Instant finishedAt, Long durationMs, String errorCode, String errorSummary,
                             Instant createdAt) {
    public boolean readOnly() {
        return operationClass.equals("read");
    }

    @Override
    public String toString() {
        return "ToolCallRecord[id=" + id + ", status=" + status + "]";
    }
}
