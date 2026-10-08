package com.stonewu.agenteam.model.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_listing 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_listing")
public class AgentListingRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "agent_id")
    private String agentId;

    @TableField(value = "listed")
    private Integer listed;

    @TableField(value = "hire_policy")
    private String hirePolicy;

    @TableField(value = "updated_by")
    private String updatedBy;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
