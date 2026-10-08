package com.stonewu.agenteam.model.schedule.entity;

import java.time.Instant;
import java.util.List;

/**
 * 一次调度检查的明确结果：哪些时间已错过、是否执行一次、未来时间和暂停原因。
 */
public record ScheduleDuePlan(List<Instant> missed, Instant executeAt, Instant nextRunAt, boolean disable,
                              String pauseReason) {
    public ScheduleDuePlan {
        missed = List.copyOf(missed);
    }
}
