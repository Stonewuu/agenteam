package com.stonewu.agenteam.model.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_hire 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_hire")
public class AgentHireRow {
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

    @TableField(value = "hired_at")
    private Instant hiredAt;

    @TableField(value = "last_used_at")
    private Instant lastUsedAt;

    @TableField(value = "paused_at")
    private Instant pausedAt;

    @TableField(value = "terminated_at")
    private Instant terminatedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
