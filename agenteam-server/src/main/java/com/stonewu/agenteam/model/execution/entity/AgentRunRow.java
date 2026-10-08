package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_run 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_run")
public class AgentRunRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "conversation_id")
    private String conversationId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "input_message_id")
    private String inputMessageId;

    @TableField(value = "output_message_id")
    private String outputMessageId;

    @TableField(value = "agent_version_id")
    private String agentVersionId;

    @TableField(value = "mode")
    private String mode;

    @TableField(value = "status")
    private String status;

    @TableField(value = "execution_config_json")
    private String executionConfigJson;

    @TableField(value = "current_attempt_no")
    private Integer currentAttemptNo;

    @TableField(value = "max_attempts")
    private Integer maxAttempts;

    @TableField(value = "lease_version")
    private Long leaseVersion;

    @TableField(value = "used_steps")
    private Integer usedSteps;

    @TableField(value = "counted_tools_json")
    private String countedToolsJson;

    @TableField(value = "active_millis")
    private Long activeMillis;

    @TableField(value = "active_segment_started_at")
    private Instant activeSegmentStartedAt;

    @TableField(value = "execution_phase")
    private String executionPhase;

    @TableField(value = "queued_at")
    private Instant queuedAt;

    @TableField(value = "has_step_errors")
    private Integer hasStepErrors;

    @TableField(value = "last_sequence")
    private Long lastSequence;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @TableField(value = "next_attempt_at")
    private Instant nextAttemptAt;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "error_message")
    private String errorMessage;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;

    @TableField(value = "active_conversation_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeConversationKey;
}
