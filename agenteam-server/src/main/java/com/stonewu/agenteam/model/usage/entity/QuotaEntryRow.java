package com.stonewu.agenteam.model.usage.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 quota_entry 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("quota_entry")
public class QuotaEntryRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "bucket_id")
    private String bucketId;

    @TableField(value = "state")
    private String state;

    @TableField(value = "quantity")
    private Integer quantity;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
