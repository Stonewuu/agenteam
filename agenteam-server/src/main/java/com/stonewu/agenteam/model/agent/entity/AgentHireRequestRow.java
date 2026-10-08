package com.stonewu.agenteam.model.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_hire_request 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_hire_request")
public class AgentHireRequestRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "agent_id")
    private String agentId;

    @TableField(value = "status")
    private String status;

    // 空值用于标识已结束的申请，插入时必须保留，不能改用数据库默认值。
    @TableField(value = "pending_marker", insertStrategy = FieldStrategy.ALWAYS)
    private Integer pendingMarker;

    @TableField(value = "request_note")
    private String requestNote;

    @TableField(value = "decision_note")
    private String decisionNote;

    @TableField(value = "decided_by")
    private String decidedBy;

    @TableField(value = "decided_at")
    private Instant decidedAt;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
