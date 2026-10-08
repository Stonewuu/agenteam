package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 resource 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("resource")
public class ResourceRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "kind")
    private String kind;

    @TableField(value = "name")
    private String name;

    @TableField(value = "subtype")
    private String subtype;

    @TableField(value = "description")
    private String description;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "source")
    private String source;

    @TableField(value = "source_reference")
    private String sourceReference;

    @TableField(value = "status")
    private String status;

    @TableField(value = "published_version_id")
    private String publishedVersionId;

    @TableField(value = "next_version_no")
    private Integer nextVersionNo;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "deleted_token")
    private String deletedToken;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
