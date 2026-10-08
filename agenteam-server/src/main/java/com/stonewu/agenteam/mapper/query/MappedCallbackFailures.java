package com.stonewu.agenteam.mapper.query;

import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;

/**
 * 映射框架包装回调异常时，保留既有的业务错误和取消行为。
 */
public final class MappedCallbackFailures {
    private MappedCallbackFailures() {
    }

    public static RuntimeException propagate(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException api) {
                return api;
            }
            if (cause instanceof ExecutionStoppedException stopped) {
                return stopped;
            }
        }
        return failure;
    }
}
