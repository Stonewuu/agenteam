package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.ApiErrorResponse;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.LoginRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一构造响应；来源与防伪过滤器也使用同一套错误结构。
 */
@Service
public class ApiResponses {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiResponses.class);
    private static final String LOGGED_EXCEPTION_ATTRIBUTE = ApiResponses.class.getName() + ".loggedException";
    private final Clock clock;
    private final ObjectMapper json;

    public ApiResponses(Clock clock, ObjectMapper json) {
        this.clock = clock;
        this.json = json;
    }

    public String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String id) {
            return id;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE, id);
        return id;
    }

    public <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, new ApiResponse.Meta(requestId(request), clock.instant().toString()));
    }

    public ResponseEntity<ApiResponse<Object>> operation(ApiOperationResult result, HttpServletRequest request) {
        return ResponseEntity.status(result.status()).body(success(result.data(), request));
    }

    public ResponseEntity<ApiErrorResponse> failure(Exception exception, HttpServletRequest request) {
        var result = errorResponse(exception, requestId(request));
        logFailure(exception, request, result.getStatusCode().value());
        return result;
    }

    /**
     * 响应已开始发送时也能记录失败；不采集请求正文、查询参数或请求头。
     */
    public void logFailure(Exception exception, HttpServletRequest request, int status) {
        if (request.getAttribute(LOGGED_EXCEPTION_ATTRIBUTE) == exception) {
            return;
        }
        String code = exception instanceof ApiException business ? business.code() : exception.getClass()
            .getSimpleName();
        if (status >= 500) {
            LOGGER.error("请求处理失败，请求编号 {}，请求方法 {}，请求路径 {}，响应状态 {}，错误代码 {}",
                requestId(request), request.getMethod(), request.getRequestURI(), status, code, exception);
        } else {
            LOGGER.warn("请求未能完成，请求编号 {}，请求方法 {}，请求路径 {}，响应状态 {}，错误代码 {}",
                requestId(request), request.getMethod(), request.getRequestURI(), status, code, exception);
        }
        request.setAttribute(LOGGED_EXCEPTION_ATTRIBUTE, exception);
    }

    private ResponseEntity<ApiErrorResponse> errorResponse(Exception exception, String requestId) {
        if (exception instanceof ApiException business) {
            return ResponseEntity.status(business.getStatusCode()).headers(business.getHeaders())
                .body(new ApiErrorResponse(
                    new ApiErrorResponse.Error(business.code(), business.getReason(), requestId, business.fieldErrors(),
                        business.details())));
        }
        if (exception instanceof MethodArgumentNotValidException invalid) {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            invalid.getBindingResult().getFieldErrors().stream().limit(20).forEach(error ->
                fields.putIfAbsent(error.getField(),
                    List.of(error.getDefaultMessage() == null ? "请检查填写的内容。" : error.getDefaultMessage())));
            return ResponseEntity.status(422).body(new ApiErrorResponse(
                new ApiErrorResponse.Error("VALIDATION_FAILED", RequestValidationErrors.summary(fields), requestId,
                    fields, Map.of())));
        }
        if (exception instanceof LoginRateLimitException limited) {
            return ResponseEntity.status(limited.getStatusCode()).headers(limited.getHeaders())
                .body(new ApiErrorResponse(
                    new ApiErrorResponse.Error("RATE_LIMITED", limited.getReason(), requestId, Map.of(),
                        Map.of("retryAfterSeconds", limited.retryAfterSeconds()))));
        }
        if (exception instanceof ResponseStatusException status) {
            String code = switch (status.getStatusCode().value()) {
                case 400, 409, 422 -> "VALIDATION_FAILED";
                case 401 -> "AUTH_REQUIRED";
                case 403 -> "PERMISSION_DENIED";
                case 404 -> "RESOURCE_NOT_FOUND";
                case 428 -> "VERSION_REQUIRED";
                case 429 -> "RATE_LIMITED";
                case 503 -> "SERVICE_UNAVAILABLE";
                default -> "INTERNAL_ERROR";
            };
            String message = status.getStatusCode()
                .is5xxServerError() ? "服务暂时不可用，请稍后重试。" : status.getReason();
            return response(status.getStatusCode().value(), code, message == null ? "请求无法完成。" : message,
                requestId);
        }
        if (exception instanceof HttpMessageNotReadableException unreadable) {
            if (unreadable.getCause() instanceof JsonMappingException mapping) {
                return ResponseEntity.badRequest()
                    .body(errorResponse(RequestValidationErrors.mapping(mapping, ""), requestId).getBody());
            }
            if (unreadable.getCause() instanceof DatabindException mapping) {
                var references = mapping.getPath().stream()
                    .filter(reference -> reference.getPropertyName() != null || reference.getIndex() >= 0)
                    .map(
                        reference -> reference.getPropertyName() != null ? reference.getPropertyName() : Integer.toString(
                            reference.getIndex())).toList();
                var invalid = RequestValidationErrors.mapping(mapping, "", references,
                    mapping instanceof UnrecognizedPropertyException unknown ? unknown.getPropertyName() : null,
                    mapping instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null);
                return ResponseEntity.badRequest().body(errorResponse(invalid, requestId).getBody());
            }
            return response(400, "VALIDATION_FAILED", "请求内容格式不正确，请检查括号、引号和字段分隔符。", requestId);
        }
        if (exception instanceof NoResourceFoundException) {
            return response(404, "RESOURCE_NOT_FOUND", "记录不存在或无法访问。", requestId);
        }
        if (exception instanceof DataAccessResourceFailureException) {
            return response(503, "SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后重试。", requestId);
        }
        if (exception instanceof ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            return response(framework.getStatusCode().value(), "VALIDATION_FAILED",
                "请求格式或操作方式不正确，请重新操作。", requestId);
        }
        return response(500, "INTERNAL_ERROR", "请求暂时无法完成，请稍后重试。", requestId);
    }

    public void writeFailure(Exception exception, HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        var result = failure(exception, request);
        response.setStatus(result.getStatusCode().value());
        result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), result.getBody());
    }

    private ResponseEntity<ApiErrorResponse> response(int status, String code, String message, String requestId) {
        return ResponseEntity.status(status)
            .body(new ApiErrorResponse(new ApiErrorResponse.Error(code, message, requestId, Map.of(), Map.of())));
    }
}
