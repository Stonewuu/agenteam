package com.stonewu.agenteam.controller.http;

import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 统一业务接口的错误格式，浏览器已断开时不再尝试写入响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final ApiResponses responses;

    public ApiExceptionHandler(ApiResponses responses) {
        this.responses = responses;
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(AsyncRequestNotUsableException exception, HttpServletRequest request,
                             HttpServletResponse response) {
        // 对方已经关闭连接，不能在事件流中再次写入 JSON 错误响应。
        responses.logFailure(exception, request, response.getStatus());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception exception, HttpServletRequest request) throws Exception {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        boolean versionOne = request.getRequestURI()
            .startsWith(request.getContextPath() + "/api/v1/") || (pattern != null && pattern.toString()
            .startsWith("/api/v1/"));
        if (!versionOne) {
            responses.logFailure(exception, request,
                exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500);
            if (exception instanceof ResponseStatusException status) {
                return ResponseEntity.status(status.getStatusCode()).headers(status.getHeaders())
                    .body(status.getBody());
            }
            throw exception;
        }
        return responses.failure(exception, request);
    }
}
