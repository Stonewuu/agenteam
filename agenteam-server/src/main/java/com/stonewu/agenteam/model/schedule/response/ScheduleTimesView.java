package com.stonewu.agenteam.model.schedule.response;

import java.util.List;

/**
 * 返回实际执行时刻和确实发生的时区调整。
 */
public record ScheduleTimesView(String timezone, List<String> times, List<String> warnings) {
}
