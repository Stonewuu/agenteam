package com.stonewu.agenteam.model.schedule.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.response.RunAccepted;

/** 操作提交结果与本次状态共同保存；排入外部通知仍保持执行中。 */
public record ScheduleActionSubmission(String status, String reasonCode, String errorSummary, RunAccepted run,
                                       JsonNode result) {
}
