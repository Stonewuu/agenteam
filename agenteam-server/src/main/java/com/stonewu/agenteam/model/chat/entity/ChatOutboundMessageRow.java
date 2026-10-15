package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 独立于业务执行的群消息发送工作；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_outbound_message")
public class ChatOutboundMessageRow implements EnterpriseScopedRecord {
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

    @TableField("action_id")
    private String actionId;

    @TableField("bot_binding_id")
    private String botBindingId;

    @TableField("kind")
    private String kind;

    @TableField("dedupe_key")
    private String dedupeKey;

    @TableField("request_id")
    private String requestId;

    @TableField("task_sequence")
    private Long taskSequence;

    @TableField("part_no")
    private Integer partNo;

    @TableField("depends_on_outbound_id")
    private String dependsOnOutboundId;

    @TableField("supersedes_outbound_id")
    private String supersedesOutboundId;

    @TableField("payload_encrypted")
    private String payloadEncrypted;

    @TableField("payload_hash")
    private String payloadHash;

    @TableField("rendered_payload_encrypted")
    private String renderedPayloadEncrypted;

    @TableField("rendered_hash")
    private String renderedHash;

    @TableField("prepared_at")
    private Instant preparedAt;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("first_submitted_at")
    private Instant firstSubmittedAt;

    @TableField("platform_message_id")
    private String platformMessageId;

    @TableField("platform_thread_id")
    private String platformThreadId;

    @TableField("error_code")
    private String errorCode;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("lease_version")
    private Long leaseVersion;
}
