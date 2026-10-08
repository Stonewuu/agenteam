package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_message 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_message")
public class AgentMessageRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "conversation_id")
    private String conversationId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "attempt_no")
    private Integer attemptNo;

    @TableField(value = "role")
    private String role;

    @TableField(value = "content")
    private String content;

    @TableField(value = "status")
    private String status;

    @TableField(value = "blocks_json")
    private String blocksJson;

    @TableField(value = "context_json")
    private String contextJson;

    @TableField(value = "last_sequence")
    private Long lastSequence;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
