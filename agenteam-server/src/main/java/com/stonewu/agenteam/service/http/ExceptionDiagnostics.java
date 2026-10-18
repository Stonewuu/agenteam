package com.stonewu.agenteam.service.http;

import java.util.IdentityHashMap;
import java.util.Map;

/** 解析异常可能带入响应正文，保留异常类型、调用位置和原因层级，移除原始文本。 */
public final class ExceptionDiagnostics {
    private ExceptionDiagnostics() {
    }

    public static Throwable withoutMessages(Throwable failure) {
        return copy(failure, new IdentityHashMap<>());
    }

    private static Throwable copy(Throwable failure, Map<Throwable, Throwable> visited) {
        if (failure == null || visited.containsKey(failure)) {
            return null;
        }
        var safe = new IllegalStateException("响应解析失败，原异常类型：" + failure.getClass().getName());
        visited.put(failure, safe);
        safe.setStackTrace(failure.getStackTrace());
        var cause = copy(failure.getCause(), visited);
        if (cause != null) {
            safe.initCause(cause);
        }
        for (var suppressed : failure.getSuppressed()) {
            var item = copy(suppressed, visited);
            if (item != null) {
                safe.addSuppressed(item);
            }
        }
        return safe;
    }
}
