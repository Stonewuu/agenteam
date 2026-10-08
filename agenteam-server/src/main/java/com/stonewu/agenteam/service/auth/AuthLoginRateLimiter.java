package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.LoginAttemptMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.LongSupplier;

/**
 * 登录前限制来源与账号，计数服务不可用时不能绕过检查。
 */
@Service
public class AuthLoginRateLimiter {

    private final LoginAttemptMapper attempts;

    public AuthLoginRateLimiter(LoginAttemptMapper attempts) {
        this.attempts = attempts;
    }

    public void checkSource(String source) {
        check(execute(() -> attempts.checkSource(source)));
    }

    public void checkAccount(String account) {
        check(execute(() -> attempts.checkAccount(account)));
    }

    public void failed(String account) {
        execute(() -> attempts.failed(account));
    }

    public void succeeded(String account) {
        execute(() -> attempts.succeeded(account));
    }

    private static void check(long retryAfter) {
        if (retryAfter > 0) {
            throw new LoginRateLimitException(retryAfter);
        }
    }

    private static long execute(LongSupplier operation) {
        try {
            return operation.getAsLong();
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂不可用，请稍后再试");
        }
    }
}
