package com.stonewu.agenteam.model.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 每次真正发送请求的追加记录，不直接作为接口响应。 */
@Getter
@Setter
@TableName("notification_delivery_attempt")
public class NotificationChannelAttemptRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("delivery_id")
    private String deliveryId;

    @TableField("attempt_no")
    private Integer attemptNo;

    @TableField("background_job_id")
    private String backgroundJobId;

    @TableField("lease_version")
    private Long leaseVersion;

    @TableField("outcome")
    private String outcome;

    @TableField("http_status")
    private Integer httpStatus;

    @TableField("provider_code")
    private String providerCode;

    @TableField("provider_request_trace")
    private String providerRequestTrace;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_summary")
    private String errorSummary;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("finished_at")
    private Instant finishedAt;
}
