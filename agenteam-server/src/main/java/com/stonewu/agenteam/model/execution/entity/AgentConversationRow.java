package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 agent_conversation 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("agent_conversation")
public class AgentConversationRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "agent_id")
    private String agentId;

    @TableField(value = "preview_resource_id")
    private String previewResourceId;

    @TableField(value = "agent_version_id")
    private String agentVersionId;

    @TableField(value = "hire_id")
    private String hireId;

    @TableField(value = "title")
    private String title;

    @TableField(value = "title_customized")
    private Integer titleCustomized;

    @TableField(value = "mode")
    private String mode;

    @TableField(value = "approval_policy")
    private String approvalPolicy;

    @TableField(value = "model_profile_id")
    private String modelProfileId;

    @TableField(value = "reasoning_effort")
    private String reasoningEffort;

    @TableField(value = "project_id")
    private String projectId;

    @TableField(value = "status")
    private String status;

    @TableField(value = "favorite")
    private Integer favorite;

    @TableField(value = "active_run_id")
    private String activeRunId;

    @TableField(value = "last_sequence")
    private Long lastSequence;

    @TableField(value = "event_delivery_mode")
    private String eventDeliveryMode;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
