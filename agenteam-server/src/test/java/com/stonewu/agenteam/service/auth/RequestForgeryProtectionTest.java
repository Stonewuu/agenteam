package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

class RequestForgeryProtectionTest {
    private final RequestForgeryProtection protection = new RequestForgeryProtection();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://new-domain.example.test", "http://localhost:5173", "http://192.0.2.10:4300", "http://[::1]:4173", "null"})
    void validSessionTokenDoesNotDependOnThePageAddress(String origin) {
        var session = new MockHttpSession();
        var request = request(session, protection.token(session));
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        request.addHeader("Referer", "https://another-address.example.test/login");
        assertDoesNotThrow(() -> protection.verify(request));
    }

    @Test
    void validTokenAlsoWorksWithoutOriginOrRefererHeaders() {
        var session = new MockHttpSession();
        assertDoesNotThrow(() -> protection.verify(request(session, protection.token(session))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"incorrect-token"})
    void missingOrIncorrectTokenIsStillRejected(String submitted) {
        var session = new MockHttpSession();
        protection.token(session);
        var request = request(session, submitted);
        request.addHeader("Origin", "https://new-domain.example.test");
        assertInvalid(request);
    }

    @Test
    void tokenCannotBeUsedWithoutItsSessionOrByAnotherSession() {
        var first = new MockHttpSession();
        String token = protection.token(first);
        assertInvalid(request(null, token));
        var second = new MockHttpSession();
        protection.token(second);
        assertInvalid(request(second, token));
        assertInvalid(request(first, "x".repeat(257)));
    }

    @Test
    void rotatingTheTokenInvalidatesThePreviousValue() {
        var session = new MockHttpSession();
        String previous = protection.token(session);
        assertEquals(previous, protection.token(session));
        protection.rotate(session);
        String current = protection.token(session);
        assertNotEquals(previous, current);
        assertInvalid(request(session, previous));
        assertDoesNotThrow(() -> protection.verify(request(session, current)));
    }

    private MockHttpServletRequest request(MockHttpSession session, String token) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        if (session != null) {
            request.setSession(session);
        }
        if (token != null) {
            request.addHeader("X-CSRF-Token", token);
        }
        return request;
    }

    private void assertInvalid(MockHttpServletRequest request) {
        var error = assertThrows(ApiException.class, () -> protection.verify(request));
        assertEquals("CSRF_INVALID", error.code());
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
