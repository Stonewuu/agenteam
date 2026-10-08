package com.stonewu.agenteam.model.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 auth_token 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("auth_token")
public class AuthTokenRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "purpose")
    private String purpose;

    @TableField(value = "token_hash")
    private String tokenHash;

    @TableField(value = "target_email")
    private String targetEmail;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "consumed_at")
    private Instant consumedAt;

    @TableField(value = "created_at")
    private Instant createdAt;
}
