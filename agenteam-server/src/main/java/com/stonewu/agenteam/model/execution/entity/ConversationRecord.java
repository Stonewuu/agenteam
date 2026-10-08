package com.stonewu.agenteam.model.execution.entity;

import java.time.Instant;

/**
 * 会话的固定员工、归属与已提交事件位置。
 */
public record ConversationRecord(String id, String enterpriseId, String userId, String agentId,
                                 String agentVersionId, String hireId, String title, String agentName,
                                 String agentIcon, String agentColor,
                                 String mode, String status, boolean favorite, String activeRunId,
                                 long lastSequence, long revision, Instant deletedAt,
                                 Instant createdAt, Instant updatedAt, String previewResourceId, String approvalPolicy,
                                 ModelSelection modelSelection, String projectId, String eventDeliveryMode) {
    public ConversationRecord(String id, String enterpriseId, String userId, String agentId,
                              String agentVersionId, String hireId, String title, String agentName,
                              String agentIcon, String agentColor, String mode, String status, boolean favorite,
                              String activeRunId, long lastSequence, long revision, Instant deletedAt,
                              Instant createdAt, Instant updatedAt, String previewResourceId, String approvalPolicy,
                              ModelSelection modelSelection, String projectId) {
        this(id, enterpriseId, userId, agentId, agentVersionId, hireId, title, agentName, agentIcon, agentColor,
            mode, status, favorite, activeRunId, lastSequence, revision, deletedAt, createdAt, updatedAt,
            previewResourceId, approvalPolicy, modelSelection, projectId, "database");
    }

    public boolean liveEvents() {
        return "redis".equals(eventDeliveryMode);
    }
}
