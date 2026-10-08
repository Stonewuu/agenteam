package com.stonewu.agenteam.model.memory.entity;

import java.time.Instant;

/**
 * 偏好属于当前企业中的本人和指定智能体，不归资源维护者所有。
 */
public record MemoryRecord(String id, String enterpriseId, String userId, String agentId, String memoryKey,
                           String content, String sourceMessageId, Instant expiresAt, long revision, Instant createdAt,
                           Instant updatedAt) {
}
