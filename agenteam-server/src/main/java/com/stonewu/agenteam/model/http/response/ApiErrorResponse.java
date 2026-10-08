package com.stonewu.agenteam.model.http.response;

import java.util.List;
import java.util.Map;

/**
 * 稳定业务错误，不包含异常堆栈、请求正文或数据库语句。
 */
public record ApiErrorResponse(Error error) {
    public record Error(String code, String message, String requestId, Map<String, List<String>> fieldErrors,
                        Map<String, ?> details) {
    }
}
