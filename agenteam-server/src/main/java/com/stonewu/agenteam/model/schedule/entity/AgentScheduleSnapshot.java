package com.stonewu.agenteam.model.schedule.entity;

/** 本次智能体任务使用的固定输入；修改计划不会改变此快照。 */
public record AgentScheduleSnapshot(String hireId, String agentVersionId, String inputText, int maxRetries) {
}
