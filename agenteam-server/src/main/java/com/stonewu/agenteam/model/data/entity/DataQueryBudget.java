package com.stonewu.agenteam.model.data.entity;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 每次发送查询和读取结果时，重新检查同一个截止时间。
 */
public record DataQueryBudget(long deadline) {
    public int remainingMillis() {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATA_QUERY_TIMEOUT",
                "数据查询已取消或超过允许时间，请减少查询范围。");
        }
        return (int) Math.min(10000, millis);
    }

    public int remainingSeconds() {
        return Math.max(1, (remainingMillis() + 999) / 1000);
    }
}
