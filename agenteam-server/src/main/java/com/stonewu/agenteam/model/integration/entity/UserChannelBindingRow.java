package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * user_channel_binding 的数据库记录，仅由渠道服务访问，不直接序列化为页面响应。
 */
@Getter
@Setter
@TableName("user_channel_binding")
public class UserChannelBindingRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("connection_id")
    private String connectionId;

    @TableField("user_id")
    private String userId;

    @TableField("external_subject_type")
    private String externalSubjectType;

    @TableField("external_subject_id")
    private String externalSubjectId;

    @TableField("external_union_id")
    private String externalUnionId;

    @TableField("display_name")
    private String displayName;

    @TableField("status")
    private String status;

    @TableField("receive_enabled")
    private Boolean receiveEnabled;

    @TableField("external_login_enabled")
    private Boolean externalLoginEnabled;

    @TableField("authorized_at")
    private Instant authorizedAt;

    @TableField("last_authenticated_at")
    private Instant lastAuthenticatedAt;

    @TableField("revoked_at")
    private Instant revokedAt;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
