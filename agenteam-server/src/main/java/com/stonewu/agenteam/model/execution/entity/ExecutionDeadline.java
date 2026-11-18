package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.system.entity.LimitValue;

import java.time.Duration;
import java.time.Instant;

/** 将业务的无时限转换为计时接口支持的值，避免纳秒换算或截止时间相加溢出。 */
public final class ExecutionDeadline {
    public static final Duration NO_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

    private ExecutionDeadline() {
    }

    public static boolean unlimited(Duration timeout) {
        return timeout.isZero() || timeout.isNegative() || timeout.compareTo(NO_TIMEOUT) >= 0;
    }

    public static long after(Duration timeout) {
        if (unlimited(timeout)) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.addExact(System.nanoTime(), timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static long milliseconds(Duration timeout) {
        return unlimited(timeout) ? LimitValue.UNLIMITED : Math.max(1, timeout.toMillis());
    }

    public static long remainingNanos(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
    }

    public static long at(Instant now, Duration timeout) {
        return unlimited(timeout) ? LimitValue.UNLIMITED : Math.addExact(now.toEpochMilli(), timeout.toMillis());
    }
}
