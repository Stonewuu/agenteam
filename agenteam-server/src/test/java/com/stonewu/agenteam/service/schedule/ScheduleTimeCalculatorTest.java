package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule.Frequency;
import com.stonewu.agenteam.model.schedule.entity.ScheduleTime.Adjustment;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 比较用户所选时间与真实时刻，覆盖月份缺少日期和不同时区调整幅度。
 */
class ScheduleTimeCalculatorTest {
    private final ScheduleTimeCalculator calculator = new ScheduleTimeCalculator();

    @Test
    void monthlyThirtyFirstSkipsMonthsWithoutThatDate() {
        var rule = rule(Frequency.MONTHLY, "09:00", "Asia/Shanghai", Set.of(), 31);
        var times = calculator.next(rule, Instant.parse("2026-01-30T00:00:00Z"), 5);
        assertEquals(List.of("2026-01-31T01:00:00Z", "2026-03-31T01:00:00Z", "2026-05-31T01:00:00Z", "2026-07-31T01:00:00Z", "2026-08-31T01:00:00Z"),
            times.stream().map(time -> time.instant().toString()).toList());
    }

    @Test
    void februaryTwentyNinthUsesLeapYearsWithoutChangingTheChosenDay() {
        var rule = rule(Frequency.MONTHLY, "08:00", "UTC", Set.of(), 29);
        assertEquals(Instant.parse("2028-02-29T08:00:00Z"), calculator.next(rule, Instant.parse("2028-02-01T00:00:00Z"), 1).getFirst().instant());
        assertEquals(Instant.parse("2027-03-29T08:00:00Z"), calculator.next(rule, Instant.parse("2027-02-01T00:00:00Z"), 1).getFirst().instant());
    }

    @Test
    void weeklyRulesUseThePlansLocalDayAndExcludeAnAlreadyDueMinute() {
        var rule = rule(Frequency.WEEKLY, "08:15", "Asia/Tokyo", Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), null);
        assertEquals(List.of("2026-09-17T23:15:00Z", "2026-09-20T23:15:00Z"), calculator.next(rule, Instant.parse("2026-09-13T23:15:00Z"), 2)
            .stream().map(time -> time.instant().toString()).toList());
    }

    @Test
    void missingTimeMovesByTheActualTransitionLength() {
        var newYork = calculator.resolve(LocalDateTime.parse("2026-03-08T02:30"), ZoneId.of("America/New_York"));
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), newYork.instant());
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), newYork.actual());
        var lordHowe = calculator.resolve(LocalDateTime.parse("2026-10-04T02:15"), ZoneId.of("Australia/Lord_Howe"));
        assertEquals(Instant.parse("2026-10-03T15:45:00Z"), lordHowe.instant());
        assertEquals(LocalDateTime.parse("2026-10-04T02:45"), lordHowe.actual());
        assertEquals(Adjustment.SHIFTED_FORWARD, newYork.adjustment());
        assertEquals(Adjustment.SHIFTED_FORWARD, lordHowe.adjustment());
    }

    @Test
    void repeatedTimeOnlyUsesItsFirstOccurrenceEvenWhenCheckingBetweenTheTwo() {
        var time = calculator.resolve(LocalDateTime.parse("2026-11-01T01:30"), ZoneId.of("America/New_York"));
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), time.instant());
        assertEquals(Adjustment.FIRST_OCCURRENCE, time.adjustment());
        var once = new ScheduleRule(Frequency.ONCE, LocalDate.parse("2026-11-01"), LocalTime.parse("01:30"), Set.of(), null, ZoneId.of("America/New_York"));
        assertTrue(calculator.next(once, Instant.parse("2026-11-01T05:45:00Z"), 5).isEmpty());
        var daily = rule(Frequency.DAILY, "01:30", "America/New_York", Set.of(), null);
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), calculator.next(daily, Instant.parse("2026-11-01T05:45:00Z"), 1).getFirst().instant());
    }

    @Test
    void aSkippedWholeLocalDayDoesNotProduceTwoExecutionsAtTheSameInstant() {
        var rule = rule(Frequency.DAILY, "12:00", "Pacific/Apia", Set.of(), null);
        var times = calculator.next(rule, Instant.parse("2011-12-30T00:00:00Z"), 3);
        assertEquals(List.of("2011-12-30T22:00:00Z", "2011-12-31T22:00:00Z", "2012-01-01T22:00:00Z"), times.stream().map(time -> time.instant().toString()).toList());
        assertEquals(Adjustment.SHIFTED_FORWARD, times.getFirst().adjustment());
        assertEquals(LocalDateTime.parse("2011-12-30T12:00"), times.getFirst().requested());
    }

    @Test
    void dueRangeIncludesItsBoundsAndUsesTheSameInstantsAsPreview() {
        var rule = rule(Frequency.DAILY, "02:30", "America/New_York", Set.of(), null);
        var first = Instant.parse("2026-03-07T07:30:00Z");
        var end = Instant.parse("2026-03-09T06:30:00Z");
        assertEquals(calculator.next(rule, first.minusNanos(1), 3), calculator.between(rule, first, end));
        assertEquals(List.of(), calculator.between(rule, end, first));
    }

    private ScheduleRule rule(Frequency frequency, String time, String zone, Set<DayOfWeek> days, Integer monthDay) {
        return new ScheduleRule(frequency, null, LocalTime.parse(time), days, monthDay, ZoneId.of(zone));
    }
}
