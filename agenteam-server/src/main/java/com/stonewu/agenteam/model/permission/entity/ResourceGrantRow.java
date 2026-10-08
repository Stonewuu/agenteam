package com.stonewu.agenteam.model.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 resource_grant 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource_grant")
public class ResourceGrantRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "subject_type")
    private String subjectType;

    @TableField(value = "subject_id")
    private String subjectId;

    @TableField(value = "capability")
    private String capability;

    @TableField(value = "created_by")
    private String createdBy;

    @TableField(value = "created_at")
    private Instant createdAt;
}
