package com.stonewu.agenteam.model.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 通知经一个外部应用发给一个成员的持久记录，不直接作为接口响应。 */
@Getter
@Setter
@TableName("notification_delivery")
public class NotificationChannelDeliveryRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("notification_id")
    private String notificationId;

    @TableField("recipient_user_id")
    private String recipientUserId;

    @TableField("connection_id")
    private String connectionId;

    @TableField("credential_revision")
    private Long credentialRevision;

    @TableField("delivery_reason")
    private String deliveryReason;

    @TableField("binding_id")
    private String bindingId;

    @TableField("binding_revision")
    private Long bindingRevision;

    @TableField("provider_request_id")
    private String providerRequestId;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("payload_hash")
    private String payloadHash;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("manual_retry_count")
    private Integer manualRetryCount;

    @TableField("dispatch_generation")
    private Integer dispatchGeneration;

    @TableField("active_job_id")
    private String activeJobId;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("first_send_at")
    private Instant firstSendAt;

    @TableField("retry_deadline_at")
    private Instant retryDeadlineAt;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField("provider_message_id")
    private String providerMessageId;

    @TableField("accepted_at")
    private Instant acceptedAt;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_error_summary")
    private String lastErrorSummary;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
