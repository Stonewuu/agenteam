package com.stonewu.agenteam.model.schedule.entity;

import java.util.List;

/** 已验证的明确接收人及其外部渠道，站内通知始终包含在执行中。 */
public record NotificationScheduleRecipient(String userId, List<String> connectionIds) {
}
