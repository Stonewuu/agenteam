package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule.Frequency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 停机、边界和未来计划的处理不依赖进程默认时区，也不产生历史执行堆积。
 */
class ScheduleDuePlannerTest {
    private final ScheduleDuePlanner planner = new ScheduleDuePlanner(new ScheduleTimeCalculator());
    private final ScheduleRule daily = new ScheduleRule(Frequency.DAILY, null, LocalTime.parse("09:00"), Set.of(), null, ZoneId.of("Asia/Shanghai"));

    @Test
    void recoveryOnlyExecutesTheLastRecentTimeAndRecordsOlderDaysAsMissed() {
        var first = Instant.parse("2026-09-11T01:00:00Z");
        var now = Instant.parse("2026-09-15T01:10:00Z");
        var result = planner.calculate(daily, first, first.minusSeconds(60), now);
        assertEquals(Instant.parse("2026-09-15T01:00:00Z"), result.executeAt());
        assertEquals(List.of("2026-09-11T01:00:00Z", "2026-09-12T01:00:00Z", "2026-09-13T01:00:00Z", "2026-09-14T01:00:00Z"), result.missed().stream().map(Instant::toString).toList());
        assertEquals(Instant.parse("2026-09-16T01:00:00Z"), result.nextRunAt());
        assertFalse(result.disable());
    }

    @Test
    void fifteenMinutesIsInclusiveButAnOlderDueTimeIsMissed() {
        var due = Instant.parse("2026-09-15T01:00:00Z");
        assertEquals(due, planner.calculate(daily, due, due.minusSeconds(1), due.plusSeconds(900)).executeAt());
        var missed = planner.calculate(daily, due, due.minusSeconds(1), due.plusSeconds(901));
        assertNull(missed.executeAt());
        assertEquals(List.of(due), missed.missed());
        assertFalse(missed.disable());
    }

    @Test
    void anExpiredOneTimePlanStopsFutureTriggersWithoutExecutingTheOldInput() {
        var once = new ScheduleRule(Frequency.ONCE, LocalDate.parse("2026-09-15"), LocalTime.parse("09:00"), Set.of(), null, ZoneId.of("Asia/Shanghai"));
        var due = Instant.parse("2026-09-15T01:00:00Z");
        var result = planner.calculate(once, due, due.minusSeconds(60), due.plusSeconds(1200));
        assertTrue(result.disable());
        assertNull(result.executeAt());
        assertNull(result.nextRunAt());
        assertEquals(List.of(due), result.missed());
    }

    @Test
    void overThirtyDaysPausesWithoutGeneratingAMonthOfExecutions() {
        var now = Instant.parse("2026-09-15T01:10:00Z");
        var future = Instant.parse("2026-09-30T01:00:00Z");
        var paused = planner.calculate(daily, future, now.minusSeconds(30 * 86400L + 1), now);
        assertTrue(paused.disable());
        assertEquals("SCHEDULE_RECONFIRM_REQUIRED", paused.pauseReason());
        assertTrue(paused.missed().isEmpty());
        assertNull(paused.executeAt());
        var boundary = planner.calculate(daily, future, now.minusSeconds(30 * 86400L), now);
        assertFalse(boundary.disable());
        assertEquals(future, boundary.nextRunAt());
    }

    @Test
    void aMonthlyRuleCanKeepAFarFutureDateWhenTheSchedulerHasCheckedItRecently() {
        var monthly = new ScheduleRule(Frequency.MONTHLY, null, LocalTime.parse("09:00"), Set.of(), 31, ZoneId.of("Asia/Shanghai"));
        var next = Instant.parse("2026-03-31T01:00:00Z");
        var now = Instant.parse("2026-02-28T01:00:00Z");
        var result = planner.calculate(monthly, next, now.minusSeconds(10), now);
        assertFalse(result.disable());
        assertEquals(next, result.nextRunAt());
        assertTrue(result.missed().isEmpty());
        assertNull(result.executeAt());
    }
}
