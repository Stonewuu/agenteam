package com.stonewu.agenteam.model.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 tag 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("tag")
public class TagRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "name_key")
    private String nameKey;

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
