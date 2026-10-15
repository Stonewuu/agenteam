package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 外部群聊或私聊及明确启用范围；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_conversation")
public class ChatConversationRow implements EnterpriseScopedRecord {
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

    @TableField("platform_code")
    private String platformCode;

    @TableField("platform_scope_key")
    private String platformScopeKey;

    @TableField("external_conversation_id")
    private String externalConversationId;

    @TableField("conversation_kind")
    private String conversationKind;

    @TableField("direct_binding_id")
    private String directBindingId;

    @TableField("name")
    private String name;

    @TableField("status")
    private String status;

    @TableField("ingress_mode")
    private String ingressMode;

    @TableField("ingress_bot_id")
    private String ingressBotId;

    @TableField("default_bot_id")
    private String defaultBotId;

    @TableField("trigger_mode")
    private String triggerMode;

    @TableField("result_policy")
    private String resultPolicy;

    @TableField("policy_json")
    private String policyJson;

    @TableField("last_member_check_at")
    private Instant lastMemberCheckAt;
}
