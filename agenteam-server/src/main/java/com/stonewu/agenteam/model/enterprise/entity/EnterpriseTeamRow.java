package com.stonewu.agenteam.model.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 enterprise_team 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("enterprise_team")
public class EnterpriseTeamRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "description")
    private String description;

    @TableField(value = "status")
    private String status;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;

    @TableField(value = "name_key")
    private String nameKey;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "deleted_token")
    private String deletedToken;

    @TableField(value = "revision")
    private Long revision;
}
