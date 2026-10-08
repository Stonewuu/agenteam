package com.stonewu.agenteam.model.schedule.entity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * 已验证的本地日期和时间规则；计划与实际时间计算共用。
 */
public record ScheduleRule(Frequency frequency, LocalDate date, LocalTime time, Set<DayOfWeek> weekdays,
                           Integer monthDay, ZoneId timezone) {
    public enum Frequency {ONCE, DAILY, WEEKLY, MONTHLY}

    public ScheduleRule {
        Objects.requireNonNull(frequency);
        Objects.requireNonNull(time);
        Objects.requireNonNull(timezone);
        weekdays = Set.copyOf(weekdays);
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("计划时间必须精确到分钟");
        }
        boolean valid = switch (frequency) {
            case ONCE -> date != null && weekdays.isEmpty() && monthDay == null;
            case DAILY -> date == null && weekdays.isEmpty() && monthDay == null;
            case WEEKLY -> date == null && !weekdays.isEmpty() && monthDay == null;
            case MONTHLY -> date == null && weekdays.isEmpty() && monthDay != null && monthDay >= 1 && monthDay <= 31;
        };
        if (!valid) {
            throw new IllegalArgumentException("日期、星期和月日必须符合所选重复方式");
        }
    }
}
