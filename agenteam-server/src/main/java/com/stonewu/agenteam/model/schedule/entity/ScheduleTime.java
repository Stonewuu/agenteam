package com.stonewu.agenteam.model.schedule.entity;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 保留所选本地时间与实际执行时间，供页面显示真实时区调整。
 */
public record ScheduleTime(Instant instant, LocalDateTime requested, LocalDateTime actual, Adjustment adjustment) {
    public enum Adjustment {NONE, SHIFTED_FORWARD, FIRST_OCCURRENCE}
}
