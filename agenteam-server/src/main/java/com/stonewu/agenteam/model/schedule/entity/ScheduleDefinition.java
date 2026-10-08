package com.stonewu.agenteam.model.schedule.entity;

import java.time.Instant;

/**
 * 已验证并准备保存的计划参数。
 */
public record ScheduleDefinition(String hireId, String agentVersionId, String name, String inputText, ScheduleRule rule,
                                 boolean enabled, int maxRetries, Instant nextRunAt,
                                 String actionType, int actionSchemaVersion, String actionConfigJson) {
    public ScheduleDefinition(String hireId, String agentVersionId, String name, String inputText, ScheduleRule rule,
                               boolean enabled, int maxRetries, Instant nextRunAt) {
        this(hireId, agentVersionId, name, inputText, rule, enabled, maxRetries, nextRunAt, "agent.run", 1, "{}");
    }
}
