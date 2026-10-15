package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 任务中固定版本的岗位与独立会话；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_participant")
public class CollaborationParticipantRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("task_id")
    private String taskId;

    @TableField("bot_binding_id")
    private String botBindingId;

    @TableField("agent_id")
    private String agentId;

    @TableField("agent_version_id")
    private String agentVersionId;

    @TableField("binding_revision")
    private Long bindingRevision;

    @TableField("role_name")
    private String roleName;

    @TableField("description_snapshot")
    private String descriptionSnapshot;

    @TableField("availability")
    private String availability;

    @TableField("explicitly_requested")
    private Boolean explicitlyRequested;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("project_id")
    private String projectId;
}
