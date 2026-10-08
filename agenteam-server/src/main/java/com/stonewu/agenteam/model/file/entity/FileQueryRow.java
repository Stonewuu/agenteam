package com.stonewu.agenteam.model.file.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * FileMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class FileQueryRow {
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private String resourceId;
    private String resourceVersionId;
    private String runId;
    private String purpose;
    private String originalName;
    private String mediaType;
    private Long expectedSizeBytes;
    private String expectedSha256;
    private String uploadLeaseId;
    private Long sizeBytes = 0L;
    private String sha256;
    private String storageKey;
    private String status;
    private String errorCode;
    private Timestamp uploadExpiresAt;
    private Timestamp uploadLeaseUntil;
    private Timestamp expiresAt;
    private Timestamp deletedAt;
    private Timestamp createdAt;
}
