package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * 修改绑定前验证当前账号密码；不能以企业身份证明全局账号的控制权。
 */
@Service
public class LocalAuthenticationService {
    private final AuthContextService identity;
    private final AuthSessionService sessions;
    private final AuthLoginRateLimiter limiter;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final AccountBehaviorService behavior;

    public LocalAuthenticationService(AuthContextService identity, AuthSessionService sessions,
                                      AuthLoginRateLimiter limiter, PasswordEncoder passwords, Clock clock,
                                      AccountBehaviorService behavior) {
        this.identity = identity;
        this.sessions = sessions;
        this.limiter = limiter;
        this.passwords = passwords;
        this.clock = clock;
        this.behavior = behavior;
    }

    public UserEntity requireRecent(HttpSession session) {
        var actor = identity.requireUser(session);
        behavior.requireOperation(actor.id(), AccountOperation.BIND_EXTERNAL_ACCOUNT);
        if (ChannelSessionGuard.external(session)
            || !(session.getAttribute(AuthSessionAttributes.AUTHENTICATED_AT) instanceof Number timestamp)
            || clock.millis() - timestamp.longValue() >= Duration.ofMinutes(15).toMillis()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", "请先验证当前账号密码，再继续操作。");
        }
        return actor;
    }

    public void reauthenticate(String password, HttpServletRequest request) {
        var user = identity.requireUser(request.getSession(false));
        limiter.checkSource(request.getRemoteAddr());
        String account = "user:" + user.id();
        limiter.checkAccount(account);
        if (password == null || password.length() > 256 || !passwords.matches(password, user.passwordHash())) {
            limiter.failed(account);
            throw ApiException.invalidField("password", "密码不正确，请重新输入。");
        }
        limiter.succeeded(account);
        identity.requireUser(sessions.establish(request, user));
    }
}
