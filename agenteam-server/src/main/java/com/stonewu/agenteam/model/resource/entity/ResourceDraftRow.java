package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 resource_draft 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource_draft")
public class ResourceDraftRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "schema_version")
    private Integer schemaVersion;

    @TableField(value = "config_json")
    private String configJson;

    @TableField(value = "validation_json")
    private String validationJson;

    @TableField(value = "config_hash")
    private String configHash;

    @TableField(value = "updated_by")
    private String updatedBy;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
