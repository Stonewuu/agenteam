package com.stonewu.agenteam.model.background.entity;

import java.time.Instant;

/**
 * 查询只允许公开任务类型，任务内部条件不进入接口响应。
 */
public record PublicJobRecord(String id, String enterpriseId, String ownerUserId, String kind, String payloadJson,
                              String status,
                              String resultFileId, String errorSummary, Instant createdAt, Instant updatedAt) {
    @Override
    public String toString() {
        return "PublicJobRecord[id=" + id + ", kind=" + kind + ", status=" + status + "]";
    }
}
