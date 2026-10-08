package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule.Frequency;
import com.stonewu.agenteam.model.schedule.entity.ScheduleTime;
import com.stonewu.agenteam.model.schedule.entity.ScheduleTime.Adjustment;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.TreeMap;

/**
 * 预览与调度使用相同规则；不存在的月日跳过，重复本地时间只取第一次。
 */
@Component
public class ScheduleTimeCalculator {
    public List<ScheduleTime> next(ScheduleRule rule, Instant after, int count) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("读取的计划时间数量必须在一至一百之间");
        }
        if (rule.frequency() == Frequency.ONCE) {
            var time = resolve(rule.date().atTime(rule.time()), rule.timezone());
            return time.instant().isAfter(after) ? List.of(time) : List.of();
        }
        var first = after.atZone(rule.timezone()).toLocalDate().minusDays(2);
        var end = first.plusYears(10);
        var result = new TreeMap<Instant, ScheduleTime>();
        for (var day = first; !day.isAfter(end) && result.size() < count; day = day.plusDays(1)) {
            if (!matches(rule, day)) {
                continue;
            }
            var time = resolve(day.atTime(rule.time()), rule.timezone());
            if (time.instant().isAfter(after)) {
                result.putIfAbsent(time.instant(), time);
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * 调度只枚举三十天内的候选；更久没有检查的计划由调用方暂停。
     */
    public List<ScheduleTime> between(ScheduleRule rule, Instant from, Instant through) {
        if (through.isBefore(from)) {
            return List.of();
        }
        if (Duration.between(from, through).compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("超过三十天的计划需要重新确认时间");
        }
        return next(rule, from.minusNanos(1), 33).stream().filter(time -> !time.instant().isAfter(through)).toList();
    }

    public ScheduleTime resolve(LocalDateTime requested, ZoneId zone) {
        var rules = zone.getRules();
        var offsets = rules.getValidOffsets(requested);
        if (offsets.size() == 1) {
            return new ScheduleTime(requested.toInstant(offsets.getFirst()), requested, requested, Adjustment.NONE);
        }
        if (offsets.size() == 2) {
            return new ScheduleTime(requested.toInstant(offsets.getFirst()), requested, requested,
                Adjustment.FIRST_OCCURRENCE);
        }
        var transition = rules.getTransition(requested);
        if (transition == null || !transition.isGap()) {
            throw new IllegalArgumentException("无法计算所选时间的时区变化");
        }
        var actual = requested.plus(transition.getDuration());
        return new ScheduleTime(actual.toInstant(transition.getOffsetAfter()), requested, actual,
            Adjustment.SHIFTED_FORWARD);
    }

    private boolean matches(ScheduleRule rule, LocalDate date) {
        return switch (rule.frequency()) {
            case ONCE -> date.equals(rule.date());
            case DAILY -> true;
            case WEEKLY -> rule.weekdays().contains(date.getDayOfWeek());
            case MONTHLY -> date.getDayOfMonth() == rule.monthDay();
        };
    }
}
