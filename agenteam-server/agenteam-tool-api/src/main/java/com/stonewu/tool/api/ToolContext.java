package com.stonewu.tool.api;

import java.time.Duration;

/**
 * 宿主核定的本次调用身份、期限及取消机制；扩展不能自行变更执行身份。
 */
public interface ToolContext {

    String enterpriseId();

    String actorId();

    String runId();

    String operationId();

    Duration timeout();

    ToolCancellation cancellation();

    /**
     * 发送外部操作前由宿主再次检查权限、有效确认及取消状态。
     */
    void beforeSend();
}
