package com.stonewu.agenteam.model.schedule.response;

import java.util.Map;

/**
 * 只向计划本人返回实际触发、执行和错误摘要。
 */
public record ScheduleOccurrenceView(String id, String scheduleId, String triggerKind, String scheduledFor,
                                     String runId,
                                     String conversationId, String status, String reasonCode, String reason,
                                     int attemptCount,
                                     String startedAt, String finishedAt, String actionType, int actionSchemaVersion,
                                     String scheduleRevision, String snapshotOrigin, Map<String, Object> actionSnapshot,
                                     Map<String, Object> actionResult) {
}
