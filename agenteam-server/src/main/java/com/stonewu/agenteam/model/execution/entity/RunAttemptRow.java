package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 run_attempt 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("run_attempt")
public class RunAttemptRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "attempt_no")
    private Integer attemptNo;

    @TableField(value = "output_message_id")
    private String outputMessageId;

    @TableField(value = "status")
    private String status;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "error_summary")
    private String errorSummary;
}
