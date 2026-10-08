package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriodState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QuotaPeriodCalculatorTest {
    private final QuotaPeriodCalculator calculator = new QuotaPeriodCalculator();

    @Test
    void naturalMonthsUseTheSelectedZoneAndIncludeLeapDay() {
        var period = QuotaPeriod.containing(Instant.parse("2024-02-28T22:00:00Z"), "Asia/Shanghai");
        assertEquals(Instant.parse("2024-01-31T16:00:00Z"), period.start());
        assertEquals(Instant.parse("2024-02-29T16:00:00Z"), period.end());
    }

    @Test
    void changingTimezoneNeverChangesTheCurrentPeriodEvenAtItsLastMillisecond() {
        var period = QuotaPeriod.containing(Instant.parse("2026-09-15T00:00:00Z"), "Asia/Shanghai");
        var state = new QuotaPeriodState(period, "America/Los_Angeles");
        assertSame(state, calculator.current(state, period.end().minusMillis(1)));
    }

    @Test
    void westwardAndEastwardChangesJoinTheOldEndWithoutAnExtraShortMonth() {
        var original = QuotaPeriod.containing(Instant.parse("2026-09-15T00:00:00Z"), "Asia/Shanghai");
        var west = calculator.current(new QuotaPeriodState(original, "America/Los_Angeles"), original.end());
        assertEquals(original.end(), west.current().start());
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), west.current().end());
        assertNull(west.pendingTimezone());
        var east = calculator.current(new QuotaPeriodState(original, "Pacific/Kiritimati"), original.end());
        assertEquals(original.end(), east.current().start());
        assertEquals(Instant.parse("2026-10-31T10:00:00Z"), east.current().end());
        var next = calculator.current(west, west.current().end());
        assertEquals(west.current().end(), next.current().start());
        assertEquals("America/Los_Angeles", next.current().timezone());
    }

    @Test
    void longInactivitySkipsUnusedMonthsAndKeepsTheNewTimezone() {
        var old = QuotaPeriod.containing(Instant.parse("2024-01-15T00:00:00Z"), "Asia/Shanghai");
        var now = Instant.parse("2026-12-15T00:00:00Z");
        var current = calculator.current(new QuotaPeriodState(old, "America/Los_Angeles"), now);
        assertEquals(QuotaPeriod.containing(now, "America/Los_Angeles"), current.current());
        assertNull(current.pendingTimezone());
    }

    @Test
    void aBackwardClockDoesNotMoveAlreadyRecordedUsageIntoAnOlderMonth() {
        var period = QuotaPeriod.containing(Instant.parse("2026-09-15T00:00:00Z"), "Asia/Shanghai");
        assertThrows(IllegalStateException.class, () -> calculator.current(new QuotaPeriodState(period, null), period.start().minusSeconds(1)));
    }
}
