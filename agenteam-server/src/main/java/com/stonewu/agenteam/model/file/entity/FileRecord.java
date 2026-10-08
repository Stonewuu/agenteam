package com.stonewu.agenteam.model.file.entity;

import java.time.Instant;

/**
 * 上传声明、实际文件和业务归属分别保存，不从客户端路径推断归属。
 */
public record FileRecord(String id, String enterpriseId, String ownerUserId, String resourceId,
                         String resourceVersionId, String runId, String purpose,
                         String originalName, String mediaType, Long expectedSizeBytes, String expectedSha256,
                         Instant uploadExpiresAt,
                         String uploadLeaseId, Instant uploadLeaseUntil, long sizeBytes, String sha256,
                         String storageKey,
                         String status, String errorCode, Instant expiresAt, Instant deletedAt, Instant createdAt) {
}
