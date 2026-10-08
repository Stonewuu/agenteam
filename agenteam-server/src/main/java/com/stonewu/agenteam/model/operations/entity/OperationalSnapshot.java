package com.stonewu.agenteam.model.operations.entity;

import java.time.Instant;
import java.util.Map;

/**
 * 只包含运维所需的实际数量和时间，不包含企业编号、用户信息或任务内容。
 */
public record OperationalSnapshot(Instant readAt, Map<String, Double> values) {
    public OperationalSnapshot {
        values = Map.copyOf(values);
    }
}
