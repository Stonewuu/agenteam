package com.stonewu.agenteam.model.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 enterprise_invitation 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("enterprise_invitation")
public class EnterpriseInvitationRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "email")
    private String email;

    @TableField(value = "email_normalized")
    private String emailNormalized;

    @TableField(value = "pending_email")
    private String pendingEmail;

    @TableField(value = "display_name")
    private String displayName;

    @TableField(value = "team_ids_json")
    private String teamIdsJson;

    @TableField(value = "role_ids_json")
    private String roleIdsJson;

    @TableField(value = "note")
    private String note;

    @TableField(value = "token_hash")
    private String tokenHash;

    @TableField(value = "status")
    private String status;

    @TableField(value = "delivery_status")
    private String deliveryStatus;

    @TableField(value = "delivery_attempts")
    private Integer deliveryAttempts;

    @TableField(value = "delivery_error")
    private String deliveryError;

    @TableField(value = "created_by")
    private String createdBy;

    @TableField(value = "accepted_user_id")
    private String acceptedUserId;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "accepted_at")
    private Instant acceptedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
