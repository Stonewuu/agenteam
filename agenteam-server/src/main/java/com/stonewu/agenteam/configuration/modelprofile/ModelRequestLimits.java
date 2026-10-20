package com.stonewu.agenteam.configuration.modelprofile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 模型无输出等待和输入预算独立于整项任务的业务时限。 */
@Component
public record ModelRequestLimits(Duration idleTimeout, long maximumInputBytes) {
    public ModelRequestLimits(@Value("${execution.model.idle-timeout:PT15M}") Duration idleTimeout,
                              @Value("${execution.model.maximum-input-bytes:0}") long maximumInputBytes) {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative() || maximumInputBytes < 0) {
            throw new IllegalArgumentException("模型等待时限必须为正数，输入预算不能为负数。");
        }
        this.idleTimeout = idleTimeout;
        // 为请求转换、框架历史及并发工作保留空间，零值按当前进程的最大堆计算。
        this.maximumInputBytes = maximumInputBytes == 0
            ? Math.max(1024 * 1024, Math.min(32 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32))
            : maximumInputBytes;
    }

    public static ModelRequestLimits defaults() {
        return new ModelRequestLimits(Duration.ofMinutes(15), 0);
    }

    public Duration timeout(Duration requested) {
        return requested == null || requested.isZero() || requested.isNegative() ? idleTimeout : requested;
    }
}
