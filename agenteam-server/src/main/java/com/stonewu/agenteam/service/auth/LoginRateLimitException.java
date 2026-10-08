package com.stonewu.agenteam.service.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 告诉客户端何时可以再次尝试，不暴露账户是否存在。
 */
public final class LoginRateLimitException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
