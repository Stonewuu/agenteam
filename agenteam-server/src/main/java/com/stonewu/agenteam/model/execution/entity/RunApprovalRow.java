package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 run_approval 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("run_approval")
public class RunApprovalRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "step_id")
    private String stepId;

    @TableField(value = "tool_call_id")
    private String toolCallId;

    @TableField(value = "approver_user_id")
    private String approverUserId;

    @TableField(value = "request_hash")
    private String requestHash;

    @TableField(value = "summary_json")
    private String summaryJson;

    @TableField(value = "status")
    private String status;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "decided_at")
    private Instant decidedAt;

    @TableField(value = "decision_note")
    private String decisionNote;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
