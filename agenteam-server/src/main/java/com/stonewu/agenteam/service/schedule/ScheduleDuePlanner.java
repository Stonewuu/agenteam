package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleDuePlan;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleTime;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 停机后只选择最近十五分钟内最后一次；三十天未检查时要求重新确认时间。
 */
@Component
public class ScheduleDuePlanner {
    private final ScheduleTimeCalculator times;

    public ScheduleDuePlanner(ScheduleTimeCalculator times) {
        this.times = times;
    }

    public ScheduleDuePlan calculate(ScheduleRule rule, Instant next, Instant lastChecked, Instant now) {
        Objects.requireNonNull(lastChecked);
        Objects.requireNonNull(now);
        var oldest = now.minus(Duration.ofDays(30));
        if (lastChecked.isBefore(oldest) || next != null && next.isBefore(oldest)) {
            return new ScheduleDuePlan(List.of(), null, null, true, "SCHEDULE_RECONFIRM_REQUIRED");
        }
        if (next == null) {
            return new ScheduleDuePlan(List.of(), null, null, true, null);
        }
        if (next.isAfter(now)) {
            return new ScheduleDuePlan(List.of(), null, next, false, null);
        }
        var due = times.between(rule, next, now).stream().map(ScheduleTime::instant).toList();
        Instant latest = due.isEmpty() ? null : due.getLast();
        Instant execute = latest != null && !latest.isBefore(now.minus(Duration.ofMinutes(15))) ? latest : null;
        var missed = due.stream().filter(time -> !time.equals(execute)).toList();
        var future = times.next(rule, now, 1);
        Instant upcoming = future.isEmpty() ? null : future.getFirst().instant();
        return new ScheduleDuePlan(missed, execute, upcoming, upcoming == null, null);
    }
}
