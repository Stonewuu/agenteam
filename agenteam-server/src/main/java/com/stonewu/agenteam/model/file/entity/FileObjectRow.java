package com.stonewu.agenteam.model.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 file_object 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("file_object")
public class FileObjectRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "resource_version_id")
    private String resourceVersionId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "purpose")
    private String purpose;

    @TableField(value = "original_name")
    private String originalName;

    @TableField(value = "media_type")
    private String mediaType;

    @TableField(value = "expected_size_bytes")
    private Long expectedSizeBytes;

    @TableField(value = "expected_sha256")
    private String expectedSha256;

    @TableField(value = "upload_expires_at")
    private Instant uploadExpiresAt;

    @TableField(value = "upload_lease_id")
    private String uploadLeaseId;

    @TableField(value = "upload_lease_until")
    private Instant uploadLeaseUntil;

    @TableField(value = "size_bytes")
    private Long sizeBytes;

    @TableField(value = "sha256")
    private String sha256;

    @TableField(value = "storage_key")
    private String storageKey;

    @TableField(value = "status")
    private String status;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
