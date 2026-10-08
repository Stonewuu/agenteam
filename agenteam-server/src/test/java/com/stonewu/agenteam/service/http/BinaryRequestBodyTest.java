package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.auth.RequestForgeryProtection;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinaryRequestBodyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RequestForgeryProtection protection = mock(RequestForgeryProtection.class);
    private final AuthMapper users = mock(AuthMapper.class);
    private final BinaryRequestBodyPolicy policy = request -> request.getRequestURI().equals("/api/v1/example/import") ? 12 : 0;

    @Test
    void registeredFileKeepsExactBytesAndBindsRepeatedRequestsToItsContents() throws Exception {
        when(users.superAdminInitialized()).thenReturn(true);
        var keys = mock(ApplicationSecretKeys.class);
        when(keys.requestKey()).thenReturn(new SecretKeySpec(new byte[32], "HmacSHA256"));
        var fingerprint = new RequestFingerprint(keys, json);
        var request = request("/api/v1/example/import", "原始文件");
        byte[] input = request.getContentAsByteArray();
        var called = new AtomicBoolean();
        filter(List.of(policy)).doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> {
            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(input);
            assertThat(forwarded.getAttribute(ApiRequestFilter.VERIFIED_ATTRIBUTE)).isEqualTo(true);
            called.set(true);
        });
        verify(protection).verify(request);
        assertThat(called).isTrue();
        String first = fingerprint.bodyHash(request, Set.of());
        request.setAttribute(ApiRequestFilter.BINARY_ATTRIBUTE, "不同文件".getBytes(StandardCharsets.UTF_8));
        assertThat(fingerprint.bodyHash(request, Set.of())).isNotEqualTo(first);
        request.setAttribute(ApiRequestFilter.BINARY_ATTRIBUTE, input);
        assertThat(fingerprint.bodyHash(request, Set.of())).isEqualTo(first);
    }

    @Test
    void unregisteredBinaryRequestStillRequiresTheNormalJsonFormat() throws Exception {
        when(users.superAdminInitialized()).thenReturn(true);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter(List.of()).doFilter(request("/api/v1/example/import", "内容"), response,
            (forwarded, ignored) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(called).isFalse();
    }

    @Test
    void unknownContentLengthCannotBypassTheRegisteredSizeLimit() throws Exception {
        when(users.superAdminInitialized()).thenReturn(true);
        var request = new MockHttpServletRequest("POST", "/api/v1/example/import") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/octet-stream");
        request.setContent(new byte[13]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter(List.of(policy)).doFilter(request, response, (forwarded, ignored) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(called).isFalse();
    }

    @Test
    void duplicateRegistrationFailsClosedWithoutReadingOrExecutingTheFile() throws Exception {
        when(users.superAdminInitialized()).thenReturn(true);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter(List.of(policy, policy)).doFilter(request("/api/v1/example/import", "内容"), response,
            (forwarded, ignored) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(called).isFalse();
    }

    private MockHttpServletRequest request(String path, String content) {
        var request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/octet-stream");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ApiRequestFilter filter(List<BinaryRequestBodyPolicy> policies) {
        return new ApiRequestFilter(new ApiResponses(Clock.systemUTC(), json), protection, users, policies);
    }
}
