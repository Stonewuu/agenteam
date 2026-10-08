package com.stonewu.agenteam.model.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 enterprise 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("enterprise")
public class EnterpriseRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "name")
    private String name;

    @TableField(value = "status")
    private String status;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;

    @TableField(value = "description")
    private String description;

    @TableField(value = "contact_email")
    private String contactEmail;

    @TableField(value = "timezone")
    private String timezone;

    @TableField(value = "quota_timezone")
    private String quotaTimezone;

    @TableField(value = "pending_quota_timezone")
    private String pendingQuotaTimezone;

    @TableField(value = "quota_period_start")
    private Instant quotaPeriodStart;

    @TableField(value = "quota_period_end")
    private Instant quotaPeriodEnd;

    @TableField(value = "permission_version")
    private Long permissionVersion;

    @TableField(value = "retention_days")
    private Integer retentionDays;

    @TableField(value = "created_by")
    private String createdBy;

    @TableField(value = "revision")
    private Long revision;
}
