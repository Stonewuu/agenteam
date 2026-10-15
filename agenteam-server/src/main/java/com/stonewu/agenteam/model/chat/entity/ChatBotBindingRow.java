package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 平台机器人的智能体绑定与连接租约；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_bot_binding")
public class ChatBotBindingRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("connection_id")
    private String connectionId;

    @TableField("platform_bot_id")
    private String platformBotId;

    @TableField("agent_id")
    private String agentId;

    @TableField("version_policy")
    private String versionPolicy;

    @TableField("pinned_version_id")
    private String pinnedVersionId;

    @TableField("role_name")
    private String roleName;

    @TableField("role_description")
    private String roleDescription;

    @TableField("status")
    private String status;

    @TableField("transport")
    private String transport;

    @TableField("public_callback_key")
    private String publicCallbackKey;

    @TableField("chat_enabled")
    private Boolean chatEnabled;

    @TableField("capabilities_json")
    private String capabilitiesJson;

    @TableField("config_json")
    private String configJson;

    @TableField("checked_revision")
    private Long checkedRevision;

    @TableField("last_connected_at")
    private Instant lastConnectedAt;

    @TableField("last_message_at")
    private Instant lastMessageAt;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("lease_version")
    private Long leaseVersion;
}
