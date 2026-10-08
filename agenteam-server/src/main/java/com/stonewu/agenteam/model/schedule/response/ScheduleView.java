package com.stonewu.agenteam.model.schedule.response;

import java.util.List;

/**
 * 页面所需的计划配置、固定员工名称和实际最近结果。
 */
public record ScheduleView(String id, String revision, String createdAt, String updatedAt, String name, String hireId,
                           String agentVersionId, String inputText, String frequency, String localDate,
                           String localTime,
                           List<Integer> weekdays, Integer monthDay, String timezone, boolean enabled, int maxRetries,
                           String nextRunAt, String pauseReason, String activeOccurrenceId,
                           ScheduleOccurrenceView activeOccurrence, ScheduleOccurrenceView latestOccurrence,
                           String agentId, String agentName, Integer agentVersionNo, String agentIcon, String agentColor,
                           ScheduleActionView action) {
}
