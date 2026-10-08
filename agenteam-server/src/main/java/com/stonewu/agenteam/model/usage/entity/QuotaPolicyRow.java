package com.stonewu.agenteam.model.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 quota_policy 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("quota_policy")
public class QuotaPolicyRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "subject_type")
    private String subjectType;

    @TableField(value = "subject_id")
    private String subjectId;

    @TableField(value = "monthly_limit")
    private Long monthlyLimit;

    @TableField(value = "enabled")
    private Integer enabled;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
