package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 可排队恢复的岗位工作；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_work_item")
public class CollaborationWorkItemRow implements EnterpriseScopedRecord {
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

    @TableField("participant_id")
    private String participantId;

    @TableField("requester_work_id")
    private String requesterWorkId;

    @TableField("origin_action_id")
    private String originActionId;

    @TableField("purpose")
    private String purpose;

    @TableField("required")
    private Boolean required;

    @TableField("instruction_encrypted")
    private String instructionEncrypted;

    @TableField("status")
    private String status;

    @TableField("resume_reason")
    private String resumeReason;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("context_id")
    private String contextId;

    @TableField("run_round")
    private Integer runRound;

    @TableField("active_run_id")
    private String activeRunId;

    @TableField("result_artifact_id")
    private String resultArtifactId;

    @TableField("replacement_work_id")
    private String replacementWorkId;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("deadline_at")
    private Instant deadlineAt;

    @TableField("error_code")
    private String errorCode;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("lease_version")
    private Long leaseVersion;
}
