package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 岗位轮次与现有执行之间的唯一关联；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_run_link")
public class CollaborationRunLinkRow implements EnterpriseScopedRecord {
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

    @TableField("work_id")
    private String workId;

    @TableField("participant_id")
    private String participantId;

    @TableField("run_round")
    private Integer runRound;

    @TableField("run_id")
    private String runId;

    @TableField("context_id")
    private String contextId;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("submission_key")
    private String submissionKey;

    @TableField("terminal_observed_at")
    private Instant terminalObservedAt;

    @TableField("last_status")
    private String lastStatus;

    @TableField("requested_stop_reason")
    private String requestedStopReason;
}
