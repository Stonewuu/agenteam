package com.stonewu.agenteam.model.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_memory 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_memory")
public class AgentMemoryRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "agent_id")
    private String agentId;

    @TableField(value = "memory_key")
    private String memoryKey;

    @TableField(value = "content")
    private String content;

    @TableField(value = "source_message_id")
    private String sourceMessageId;

    @TableField(value = "expires_at")
    private Instant expiresAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
