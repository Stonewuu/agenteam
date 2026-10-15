package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 去重后的会话消息及来源身份；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_message")
public class ChatMessageRow implements EnterpriseScopedRecord {
    @TableField("content_purged_at")
    private Instant contentPurgedAt;
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

    @TableField("conversation_id")
    private String conversationId;

    @TableField("task_id")
    private String taskId;

    @TableField("canonical_key")
    private String canonicalKey;

    @TableField("platform_message_id")
    private String platformMessageId;

    @TableField("thread_id")
    private String threadId;

    @TableField("reply_to_platform_id")
    private String replyToPlatformId;

    @TableField("sender_kind")
    private String senderKind;

    @TableField("sender_user_id")
    private String senderUserId;

    @TableField("origin_binding_id")
    private String originBindingId;

    @TableField("sender_identities_encrypted")
    private String senderIdentitiesEncrypted;

    @TableField("message_type")
    private String messageType;

    @TableField("content_encrypted")
    private String contentEncrypted;

    @TableField("mentions_json")
    private String mentionsJson;

    @TableField("platform_created_at")
    private Instant platformCreatedAt;

    @TableField("received_at")
    private Instant receivedAt;

    @TableField("routing_status")
    private String routingStatus;

    @TableField("content_hash")
    private String contentHash;

    @TableField("content_version")
    private Long contentVersion;
}
