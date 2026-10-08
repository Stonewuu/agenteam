package com.stonewu.agenteam.service.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 由业务明确决定状态、错误代码和可公开的处理提示。
 */
public final class ApiException extends ResponseStatusException {
    private final String code;
    private final Map<String, ?> details;
    private final Map<String, List<String>> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        this(status, code, message, Map.of(), Map.of(), cause);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, ?> details,
                        Map<String, List<String>> fieldErrors) {
        this(status, code, message, details, fieldErrors, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, ?> details,
                        Map<String, List<String>> fieldErrors, Throwable cause) {
        super(status, message, cause);
        this.code = code;
        this.details = Map.copyOf(details);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Map<String, ?> details() {
        return details;
    }

    public Map<String, List<String>> fieldErrors() {
        return fieldErrors;
    }

    public static ApiException invalidField(String field, String message) {
        return invalidField(field, message, null);
    }

    public static ApiException invalidField(String field, String message, Throwable cause) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message, Map.of(),
            Map.of(field, List.of(message)), cause);
    }

    public static ApiException versionConflict(long revision) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "内容已被其他操作更新，请重新加载后继续编辑。",
            Map.of("currentRevision", Long.toString(revision)), Map.of());
    }
}
