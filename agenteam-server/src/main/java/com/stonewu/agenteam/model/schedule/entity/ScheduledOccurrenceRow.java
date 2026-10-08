package com.stonewu.agenteam.model.schedule.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 scheduled_occurrence 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("scheduled_occurrence")
public class ScheduledOccurrenceRow {
    @TableField("action_type")
    private String actionType;

    @TableField("action_schema_version")
    private Integer actionSchemaVersion;

    @TableField("schedule_revision")
    private Long scheduleRevision;

    @TableField("action_snapshot_json")
    private String actionSnapshotJson;

    @TableField("action_result_json")
    private String actionResultJson;

    @TableField("snapshot_origin")
    private String snapshotOrigin;

    @TableField("action_job_id")
    private String actionJobId;

    @TableField("error_summary")
    private String errorSummary;

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "schedule_id")
    private String scheduleId;

    @TableField(value = "trigger_kind")
    private String triggerKind;

    @TableField(value = "occurrence_key")
    private String occurrenceKey;

    @TableField(value = "scheduled_for")
    private Instant scheduledFor;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "conversation_id")
    private String conversationId;

    @TableField(value = "status")
    private String status;

    @TableField(value = "reason_code")
    private String reasonCode;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;

    @TableField(value = "active_schedule_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeScheduleKey;
}
