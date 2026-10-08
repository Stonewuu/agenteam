package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.controller.http.ApiExceptionHandler;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.auth.LoginRateLimitException;
import com.stonewu.agenteam.service.auth.RequestForgeryProtection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorLoggingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApiResponses responses = new ApiResponses(Clock.systemUTC(), json);

    @ParameterizedTest
    @MethodSource("failures")
    void everyErrorResponseIncludesTheExceptionStack(Exception failure, int status, CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/example");
        request.setQueryString("key=不应主动记录的查询值");
        request.addHeader("Authorization", "不应主动记录的请求头");
        request.setContent("不应主动记录的请求正文".getBytes(StandardCharsets.UTF_8));
        var result = responses.failure(failure, request);

        assertEquals(status, result.getStatusCode().value());
        assertEquals(responses.requestId(request), result.getBody().error().requestId());
        assertTrue(output.getOut().contains(status >= 500 ? "ERROR" : "WARN"));
        assertTrue(output.getOut().contains("请求编号 " + responses.requestId(request)));
        assertTrue(output.getOut().contains("请求方法 POST，请求路径 /api/v1/example，响应状态 " + status));
        assertTrue(output.getOut().contains(failure.getClass().getName()));
        assertTrue(output.getOut().contains("at " + ApiErrorLoggingTest.class.getName() + ".failures("));
        if (failure.getCause() != null) {
            assertTrue(output.getOut().contains("Caused by: " + failure.getCause().getClass().getName()));
        }
        assertFalse(output.getOut().contains("不应主动记录"));
        assertFalse(json.writeValueAsString(result.getBody()).contains("内部故障原因"));
        if (failure instanceof LoginRateLimitException) {
            assertEquals("30", result.getHeaders().getFirst("Retry-After"));
        }
    }

    private static Stream<Arguments> failures() throws Exception {
        var binding = new BeanPropertyBindingResult(Map.of(), "input");
        binding.addError(new FieldError("input", "name", "请填写名称。"));
        var parameter = new MethodParameter(ApiErrorLoggingTest.class.getDeclaredMethod("input", String.class), 0);
        return Stream.of(
            Arguments.of(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "暂时无法操作。", new IOException("内部故障原因")), 503),
            Arguments.of(ApiException.invalidField("name", "请填写名称。"), 422),
            Arguments.of(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "内部故障原因", new IOException("连接未完成")), 502),
            Arguments.of(new DataAccessResourceFailureException("内部故障原因", new IOException("数据库连接失败")), 503),
            Arguments.of(new IllegalStateException("内部故障原因", new IOException("底层操作失败")), 500),
            Arguments.of(new HttpMessageNotReadableException("内部故障原因", new IOException("内容无法解析"), new MockHttpInputMessage(new byte[0])), 400),
            Arguments.of(new MethodArgumentNotValidException(parameter, binding), 422),
            Arguments.of(new LoginRateLimitException(30), 429),
            Arguments.of(new HttpRequestMethodNotSupportedException("PUT"), 405));
    }

    private static void input(String name) {
    }

    @Test
    void filterFailuresUseTheSameLoggingAndResponse(CapturedOutput output) throws Exception {
        var filter = new ApiRequestFilter(responses, mock(RequestForgeryProtection.class), mock(AuthMapper.class), List.of());
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/bootstrap-status");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new DataAccessResourceFailureException("数据库暂不可用", new IOException("测试连接失败"));
        });
        assertEquals(503, response.getStatus());
        assertEquals(response.getHeader("X-Request-Id"), json.readTree(response.getContentAsByteArray()).path("error").path("requestId").asText());
        assertTrue(output.getOut().contains("Caused by: " + IOException.class.getName() + ": 测试连接失败"));
        assertFalse(response.getContentAsString().contains("测试连接失败"));
    }

    @Test
    void disconnectedResponsesLogTheStackOnceWithoutWritingAgain(CapturedOutput output) throws Exception {
        var handler = new ApiExceptionHandler(responses);
        var request = new MockHttpServletRequest("GET", "/api/v1/example/events");
        var response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");
        response.flushBuffer();
        var failure = new AsyncRequestNotUsableException("事件连接已关闭", new IOException("测试连接中断"));
        handler.disconnected(failure, request, response);
        responses.logFailure(failure, request, response.getStatus());
        assertEquals(1, output.getOut().lines().filter(line -> line.contains("请求未能完成，请求编号 ")).count());
        assertTrue(output.getOut().contains("Caused by: " + IOException.class.getName() + ": 测试连接中断"));
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals("text/event-stream", response.getContentType());
    }

    @Test
    void unavailableEncryptionVersionIdentifiesTheConfigurationOnlyInTheLog(CapturedOutput output, @TempDir Path directory) throws Exception {
        var keys = new ApplicationSecretKeys("", "", "current", directory.resolve("keys.properties").toString());
        var missingEncryption = assertThrows(ApiException.class, () -> keys.encryptionKey("missing"));
        var encryption = responses.failure(missingEncryption, new MockHttpServletRequest("POST", "/api/v1/example"));
        assertTrue(output.getOut().contains("未找到版本 missing 的内容加密密钥"));
        assertTrue(output.getOut().contains("Caused by: " + IllegalStateException.class.getName()));
        assertFalse(json.writeValueAsString(encryption.getBody()).contains("agenteam.security"));
    }
}
