package com.stonewu.agenteam.model.modelusage.entity;

import java.time.Instant;

/** 当地日期对应的真实 UTC 边界，夏令时当天不强行按二十四小时计算。 */
public record ModelUsageDay(String date, Instant start, Instant end) {
}
