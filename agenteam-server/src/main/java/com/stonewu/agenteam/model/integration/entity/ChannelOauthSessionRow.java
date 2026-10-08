package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * channel_oauth_session 的数据库记录，仅由渠道服务访问，不直接序列化为页面响应。
 */
@Getter
@Setter
@TableName("channel_oauth_session")
public class ChannelOauthSessionRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("connection_id")
    private String connectionId;

    @TableField("purpose")
    private String purpose;

    @TableField("initiator_user_id")
    private String initiatorUserId;

    @TableField("initiator_session_version")
    private Long initiatorSessionVersion;

    @TableField("connection_revision")
    private Long connectionRevision;

    @TableField("state_hash")
    private String stateHash;

    @TableField("browser_nonce_hash")
    private String browserNonceHash;

    @TableField("confirmation_token_hash")
    private String confirmationTokenHash;

    @TableField("transient_encrypted_json")
    private String transientEncryptedJson;

    @TableField("return_path")
    private String returnPath;

    @TableField("status")
    private String status;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField("confirmation_expires_at")
    private Instant confirmationExpiresAt;

    @TableField("consumed_at")
    private Instant consumedAt;

    @TableField("result_code")
    private String resultCode;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
