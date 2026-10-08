package com.stonewu.agenteam.model.schedule.entity;

import java.time.Instant;

/**
 * 一次预定或手动触发的实际结果，错过和被阻止也有明确记录。
 */
public record ScheduleOccurrenceRecord(String id, String enterpriseId, String scheduleId, String triggerKind,
                                       String occurrenceKey,
                                       Instant scheduledFor, String runId, String conversationId, String status,
                                       String reasonCode,
                                       String errorMessage, int attemptCount, Instant startedAt, Instant finishedAt,
                                       Instant createdAt, Instant updatedAt, String actionType, int actionSchemaVersion,
                                       Long scheduleRevision, String actionSnapshotJson, String actionResultJson,
                                       String snapshotOrigin, String actionJobId, String errorSummary) {
}
