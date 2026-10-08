package com.stonewu.agenteam.configuration.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.auth.RequestForgeryProtection;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.BinaryRequestBodyPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 普通接口验证 JSON 正文；文件上传保留流式读取，写请求均验证会话防伪令牌。
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 50)
public class ApiRequestFilter extends OncePerRequestFilter {

    public static final String JSON_ATTRIBUTE = "agenteam.request-json";

    public static final String BINARY_ATTRIBUTE = "agenteam.request-binary";

    public static final String VERIFIED_ATTRIBUTE = "agenteam.request-verified";

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final Set<String> BEFORE_INITIALIZATION = Set.of("/api/v1/auth/bootstrap-status",
        "/api/v1/auth/bootstrap", "/api/v1/auth/csrf");

    private final ApiResponses responses;

    private final RequestForgeryProtection protection;

    private final AuthMapper users;

    private final List<BinaryRequestBodyPolicy> binaryPolicies;

    private final ObjectMapper json = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BODY_BYTES).maxNumberLength(1000)
                .build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    public ApiRequestFilter(ApiResponses responses, RequestForgeryProtection protection, AuthMapper users,
                            List<BinaryRequestBodyPolicy> binaryPolicies) {
        this.responses = responses;
        this.protection = protection;
        this.users = users;
        this.binaryPolicies = List.copyOf(binaryPolicies);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Request-Id", responses.requestId(request));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!BEFORE_INITIALIZATION.contains(path) && !users.superAdminInitialized()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SYSTEM_NOT_INITIALIZED",
                    "系统尚未初始化，请联系部署人员。");
            }
            if (!SAFE_METHODS.contains(request.getMethod())) {
                protection.verify(request);
                if (request.getMethod().equals("PUT") && path.matches(
                    "/api/v1/enterprises/[^/]+/files/[^/]+/content")) {
                    if (request.getContentLengthLong() > 20L * 1024 * 1024) {
                        throw tooLarge();
                    }
                    request.setAttribute(VERIFIED_ATTRIBUTE, true);
                    chain.doFilter(request, response);
                    return;
                }
                int binaryLimit = binaryLimit(request);
                if (binaryLimit > 0) {
                    if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim()
                        .equalsIgnoreCase("application/octet-stream")) {
                        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VALIDATION_FAILED",
                            "请通过文件导入提交原始文件。");
                    }
                    if (request.getContentLengthLong() > binaryLimit) {
                        throw tooLarge();
                    }
                    byte[] body = request.getInputStream().readNBytes(binaryLimit + 1);
                    if (body.length > binaryLimit) {
                        throw tooLarge();
                    }
                    request.setAttribute(BINARY_ATTRIBUTE, body);
                    request.setAttribute(VERIFIED_ATTRIBUTE, true);
                    chain.doFilter(new CachedBodyRequest(request, body), response);
                    return;
                }
                if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                    throw tooLarge();
                }
                byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    throw tooLarge();
                }
                if (body.length > 0) {
                    if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim()
                        .equalsIgnoreCase("application/json")) {
                        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VALIDATION_FAILED",
                            "请使用页面支持的请求格式。");
                    }
                    JsonNode tree;
                    try {
                        tree = json.readTree(body);
                    } catch (IOException exception) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                            "请求内容格式不正确或包含重复字段。", exception);
                    }
                    if (tree == null || !tree.isObject()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                            "请求内容必须是完整的表单对象。");
                    }
                    request.setAttribute(JSON_ATTRIBUTE, tree);
                }
                request.setAttribute(VERIFIED_ATTRIBUTE, true);
                chain.doFilter(new CachedBodyRequest(request, body), response);
            } else {
                request.setAttribute(VERIFIED_ATTRIBUTE, true);
                chain.doFilter(request, response);
            }
        } catch (Exception exception) {
            if (response.isCommitted()) {
                responses.logFailure(exception, request, response.getStatus());
                throw exception;
            }
            responses.writeFailure(exception, request, response);
        }
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "提交内容过大，请缩减后重试。");
    }

    private int binaryLimit(HttpServletRequest request) {
        int selected = 0;
        for (var policy : binaryPolicies) {
            int limit = policy.maximumBytes(request);
            if (limit < 0 || limit > MAX_BODY_BYTES || (limit > 0 && selected > 0)) {
                throw new IllegalStateException("文件请求的大小限制或路径登记有冲突");
            }
            if (limit > 0) {
                selected = limit;
            }
        }
        return selected;
    }
}
