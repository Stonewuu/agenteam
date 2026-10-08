package com.stonewu.agenteam.model.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 quota_bucket 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("quota_bucket")
public class QuotaBucketRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "policy_id")
    private String policyId;

    @TableField(value = "period_start")
    private Instant periodStart;

    @TableField(value = "period_end")
    private Instant periodEnd;

    @TableField(value = "timezone")
    private String timezone;

    @TableField(value = "used_count")
    private Long usedCount;

    @TableField(value = "reserved_count")
    private Long reservedCount;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
