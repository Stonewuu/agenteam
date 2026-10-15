package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 原发起人的协作总任务；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_task")
public class CollaborationTaskRow implements EnterpriseScopedRecord {
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

    @TableField("conversation_id")
    private String conversationId;

    @TableField("origin_message_id")
    private String originMessageId;

    @TableField("initiator_user_id")
    private String initiatorUserId;

    @TableField("origin_user_binding_id")
    private String originUserBindingId;

    @TableField("entry_bot_id")
    private String entryBotId;

    @TableField("owner_participant_id")
    private String ownerParticipantId;

    @TableField("owner_work_id")
    private String ownerWorkId;

    @TableField("short_code")
    private String shortCode;

    @TableField("objective_encrypted")
    private String objectiveEncrypted;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("status")
    private String status;

    @TableField("output_visibility")
    private String outputVisibility;

    @TableField("reply_mode_revision")
    private Long replyModeRevision;

    @TableField("reply_mode_explicit")
    private Boolean replyModeExplicit;

    @TableField("roster_revision")
    private Long rosterRevision;

    @TableField("policy_snapshot")
    private String policySnapshot;

    @TableField("waived_requested_roles_json")
    private String waivedRequestedRolesJson;

    @TableField("next_sequence")
    private Long nextSequence;

    @TableField("deadline_at")
    private Instant deadlineAt;

    @TableField("work_count")
    private Integer workCount;

    @TableField("handoff_count")
    private Integer handoffCount;

    @TableField("run_count")
    private Integer runCount;

    @TableField("final_artifact_id")
    private String finalArtifactId;

    @TableField("error_code")
    private String errorCode;

    @TableField("finished_at")
    private Instant finishedAt;
}
