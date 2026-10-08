package com.stonewu.agenteam.model.background.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 background_job 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("background_job")
public class BackgroundJobRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "kind")
    private String kind;

    @TableField(value = "dedupe_key")
    private String dedupeKey;

    @TableField(value = "payload_json")
    private String payloadJson;

    @TableField(value = "status")
    private String status;

    @TableField(value = "available_at")
    private Instant availableAt;

    @TableField(value = "attempt_count")
    private Integer attemptCount;

    @TableField(value = "max_attempts")
    private Integer maxAttempts;

    @TableField(value = "lease_owner")
    private String leaseOwner;

    @TableField(value = "lease_version")
    private Long leaseVersion;

    @TableField(value = "lease_until")
    private Instant leaseUntil;

    @TableField(value = "heartbeat_at")
    private Instant heartbeatAt;

    @TableField(value = "result_file_id")
    private String resultFileId;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "error_summary")
    private String errorSummary;

    @TableField(value = "active_export_owner_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeExportOwnerKey;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
