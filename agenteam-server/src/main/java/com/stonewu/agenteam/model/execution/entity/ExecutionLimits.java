package com.stonewu.agenteam.model.execution.entity;

import java.time.Duration;

/**
 * 执行读取提交时固定的限制；员工来自配置，单独工作流测试来自明确保存的测试限制。
 */
public record ExecutionLimits(int maxSteps, int timeoutSeconds) {
    /**
     * 框架仅接受正整数，0 在应用中表示无步骤限制，在框架边界转为其最大整数。
     */
    public static int frameworkMaxSteps(int maxSteps) {
        return maxSteps == 0 ? Integer.MAX_VALUE : maxSteps;
    }

    /**
     * 零时长表示无上限；父任务、子任务和工具同时设置时，使用其中较短的有效上限。
     */
    public static Duration minTimeout(Duration first, Duration second) {
        if (first.isZero()) {
            return second;
        }
        if (second.isZero()) {
            return first;
        }
        return first.compareTo(second) < 0 ? first : second;
    }

    /**
     * 框架工具会强制合并默认时限，用其最大等待时间避免覆盖平台的执行及单次工具限制。
     */
    public static Duration frameworkToolTimeout(Duration timeout) {
        return timeout.isZero() ? Duration.ofNanos(Long.MAX_VALUE) : timeout;
    }

    public static ExecutionLimits from(RunRecord run) {
        var source = run.executionConfig().path(run.executionConfig().has("workflowId") ? "limits" : "config");
        return new ExecutionLimits(source.path("maxSteps").asInt(), source.path("timeoutSeconds").asInt());
    }
}
