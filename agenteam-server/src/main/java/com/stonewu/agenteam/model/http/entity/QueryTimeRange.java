package com.stonewu.agenteam.model.http.entity;

import java.time.Instant;

/**
 * 同一列表翻页继续使用首次查询的起止时间。
 */
public record QueryTimeRange(Instant from, Instant to) {
    public String cursorValue() {
        return from + "|" + to;
    }
}
