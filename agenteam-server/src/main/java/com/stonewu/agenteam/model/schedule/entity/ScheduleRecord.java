package com.stonewu.agenteam.model.schedule.entity;

import java.time.Instant;

/**
 * 计划配置和当前发生记录的引用，执行历史始终属于各自独立发生记录。
 */
public record ScheduleRecord(String id, String enterpriseId, String userId, String hireId, String agentVersionId,
                             String name, String inputText, ScheduleRule rule, boolean enabled, int maxRetries,
                             Instant nextRunAt, Instant lastCheckedAt, String pauseReason, String activeOccurrenceId,
                             long revision, Instant createdAt, Instant updatedAt, Instant deletedAt,
                             String agentId, String agentName, Integer agentVersionNo, String agentIcon,
                             String agentColor, String actionType, int actionSchemaVersion, String actionConfigJson) {
}
