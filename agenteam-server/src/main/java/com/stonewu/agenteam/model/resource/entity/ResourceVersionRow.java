package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 resource_version 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource_version")
public class ResourceVersionRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "version_no")
    private Integer versionNo;

    @TableField(value = "name")
    private String name;

    @TableField(value = "description")
    private String description;

    @TableField(value = "schema_version")
    private Integer schemaVersion;

    @TableField(value = "config_json")
    private String configJson;

    @TableField(value = "config_hash")
    private String configHash;

    @TableField(value = "release_note")
    private String releaseNote;

    @TableField(value = "status")
    private String status;

    @TableField(value = "published_by")
    private String publishedBy;

    @TableField(value = "published_at")
    private Instant publishedAt;

    @TableField(value = "revoked_at")
    private Instant revokedAt;
}
