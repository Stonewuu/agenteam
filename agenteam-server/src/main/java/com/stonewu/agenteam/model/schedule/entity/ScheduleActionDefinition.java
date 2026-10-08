package com.stonewu.agenteam.model.schedule.entity;

import java.util.List;

/** 处理器验证后交给通用计划服务的配置，关系字段继续保存在正式列和接收人表中。 */
public record ScheduleActionDefinition(String type, int schemaVersion, String configJson, String hireId,
                                       String agentVersionId, String inputText, int maxRetries, List<NotificationScheduleRecipient> recipients) {
}
