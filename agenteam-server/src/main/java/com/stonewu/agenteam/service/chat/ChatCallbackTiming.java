package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 只记录阶段和耗时，不保存回调正文、操作者身份或答案。 */
public final class ChatCallbackTiming {
    private final LongSupplier nanos;
    private final Instant receivedAt;
    private final long started;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long previous;
    private String current = Messages.log("log.chat.chatCallbackTiming.current.1");
    private Result result;

    public ChatCallbackTiming(Clock clock) {
        this(clock, System::nanoTime);
    }

    ChatCallbackTiming(Clock clock, LongSupplier nanos) {
        this.nanos = nanos;
        this.receivedAt = clock.instant();
        this.started = nanos.getAsLong();
        this.previous = started;
    }

    public void enter(String next) {
        if (result == null) {
            long now = nanos.getAsLong();
            stages.put(current, elapsed(previous, now));
            previous = now;
            current = next;
        }
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Result finish() {
        if (result == null) {
            long now = nanos.getAsLong();
            stages.put(current, elapsed(previous, now));
            result = new Result(elapsed(started, now), Collections.unmodifiableMap(new LinkedHashMap<>(stages)));
        }
        return result;
    }

    public static String eventId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,191}") ? value : Messages.log("text.chat.feishuConnectionProvider.binary.1");
    }

    private static long elapsed(long from, long to) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, to - from));
    }

    public record Result(long totalMillis, Map<String, Long> stages) {
    }
}
