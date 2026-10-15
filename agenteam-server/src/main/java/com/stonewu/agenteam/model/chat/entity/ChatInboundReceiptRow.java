package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 先保存后确认的消息接收记录；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_inbound_receipt")
public class ChatInboundReceiptRow implements EnterpriseScopedRecord {
    @TableField("payload_purged_at")
    private Instant payloadPurgedAt;
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

    @TableField("bot_binding_id")
    private String botBindingId;

    @TableField("connection_revision")
    private Long connectionRevision;

    @TableField("connection_lease_version")
    private Long connectionLeaseVersion;

    @TableField("platform_event_id")
    private String platformEventId;

    @TableField("receipt_key")
    private String receiptKey;

    @TableField("event_type")
    private String eventType;

    @TableField("platform_message_id")
    private String platformMessageId;

    @TableField("received_at")
    private Instant receivedAt;

    @TableField("payload_encrypted")
    private String payloadEncrypted;

    @TableField("message_id")
    private String messageId;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("error_code")
    private String errorCode;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("lease_version")
    private Long leaseVersion;
}
