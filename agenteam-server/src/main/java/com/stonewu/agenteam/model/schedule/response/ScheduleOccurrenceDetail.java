package com.stonewu.agenteam.model.schedule.response;

import java.util.List;

/** 发生记录的固定内容与接收结果，仅所属计划的本人可读。 */
public record ScheduleOccurrenceDetail(ScheduleOccurrenceView occurrence, List<ScheduleRecipientResult> recipients) {
}
