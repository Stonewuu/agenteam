package com.stonewu.agenteam.model.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 平台发送尝试及脱敏排障信息；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("chat_outbound_attempt")
public class ChatOutboundAttemptRow implements EnterpriseScopedRecord {
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

    @TableField("outbound_id")
    private String outboundId;

    @TableField("attempt_no")
    private Integer attemptNo;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("finished_at")
    private Instant finishedAt;

    @TableField("submitted_at")
    private Instant submittedAt;

    @TableField("outcome")
    private String outcome;

    @TableField("http_status")
    private Integer httpStatus;

    @TableField("platform_code")
    private String platformCode;

    @TableField("diagnostic_id")
    private String diagnosticId;

    @TableField("retry_after_ms")
    private Long retryAfterMs;

    @TableField("error_summary")
    private String errorSummary;

    @TableField("response_message_id")
    private String responseMessageId;
}
