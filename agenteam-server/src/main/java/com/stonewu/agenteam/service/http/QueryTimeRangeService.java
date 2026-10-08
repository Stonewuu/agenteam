package com.stonewu.agenteam.service.http;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.entity.QueryTimeRange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 审计和调用日志统一使用明确时间范围，默认七天，最长九十天。
 */
@Service
public class QueryTimeRangeService {
    private final Clock clock;

    public QueryTimeRangeService(Clock clock) {
        this.clock = clock;
    }

    public QueryTimeRange resolve(String from, String to, PagePosition cursor) {
        if (cursor != null) {
            try {
                String[] values = cursor.sortValue().split("\\|", -1);
                if (values.length != 2) {
                    throw new IllegalArgumentException();
                }
                var range = new QueryTimeRange(Instant.parse(values[0]), Instant.parse(values[1]));
                validate(range);
                return range;
            } catch (RuntimeException invalid) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "查询时间范围已失效，请重新加载列表。");
            }
        }
        Instant end = to == null ? clock.instant() : parse(to, "to");
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : parse(from, "from");
        var range = new QueryTimeRange(start, end);
        validate(range);
        return range;
    }

    public String normalize(String value, String field) {
        return value == null || value.isBlank() ? null : parse(value, field).toString();
    }

    private Instant parse(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw ApiException.invalidField(field, "请提供包含时区的有效时间。");
        }
    }

    private void validate(QueryTimeRange range) {
        if (range.from().isAfter(range.to())) {
            throw ApiException.invalidField("from", "开始时间不能晚于结束时间。");
        }
        if (Duration.between(range.from(), range.to()).compareTo(Duration.ofDays(90)) > 0) {
            throw ApiException.invalidField("from", "一次查询的时间范围不能超过九十天。");
        }
    }
}
