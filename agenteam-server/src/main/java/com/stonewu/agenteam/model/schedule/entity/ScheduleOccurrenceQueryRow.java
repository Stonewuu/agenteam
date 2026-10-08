package com.stonewu.agenteam.model.schedule.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ScheduleOccurrenceMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ScheduleOccurrenceQueryRow {
    private String actionType;
    private Integer actionSchemaVersion;
    private Long scheduleRevision;
    private String actionSnapshotJson;
    private String actionResultJson;
    private String snapshotOrigin;
    private String actionJobId;
    private String errorSummary;
    private String id;
    private String enterpriseId;
    private String scheduleId;
    private String triggerKind;
    private String occurrenceKey;
    private String runId;
    private String conversationId;
    private String status;
    private String reasonCode;
    private String errorMessage;
    private Integer attemptCount = 0;
    private Timestamp scheduledFor;
    private Timestamp startedAt;
    private Timestamp finishedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
