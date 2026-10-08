package com.stonewu.agenteam.model.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 app_user 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("app_user")
public class AppUserRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "username")
    private String username;

    @TableField(value = "username_normalized")
    private String usernameNormalized;

    @TableField(value = "password_hash")
    private String passwordHash;

    @TableField(value = "display_name")
    private String displayName;

    @TableField(value = "email")
    private String email;

    @TableField(value = "email_normalized")
    private String emailNormalized;

    @TableField(value = "email_verified_at")
    private Instant emailVerifiedAt;

    @TableField(value = "status")
    private String status;

    @TableField(value = "is_super_admin")
    private Integer isSuperAdmin;

    @TableField(value = "last_enterprise_id")
    private String lastEnterpriseId;

    @TableField(value = "session_version")
    private Long sessionVersion;

    @TableField(value = "password_changed_at")
    private Instant passwordChangedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
