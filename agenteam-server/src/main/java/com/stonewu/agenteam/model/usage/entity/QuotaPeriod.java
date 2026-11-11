package com.stonewu.agenteam.model.usage.entity;

import com.stonewu.agenteam.service.i18n.Messages;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 实际保存的计数时间范围；结束时刻属于下一周期。
 */
public record QuotaPeriod(Instant start, Instant end, String timezone) {
    public QuotaPeriod {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        ZoneId.of(timezone);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(Messages.text("error.usage.quotaPeriod.init.1"));
        }
    }

    public static QuotaPeriod containing(Instant now, String timezone) {
        var zone = ZoneId.of(timezone);
        var start = now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone);
        return new QuotaPeriod(start.toInstant(), start.toLocalDate().plusMonths(1).atStartOfDay(zone).toInstant(),
            timezone);
    }
}
