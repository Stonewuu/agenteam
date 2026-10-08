package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.model.auth.request.ApiLoginRequest;
import com.stonewu.agenteam.model.auth.response.BootstrapStatusResponse;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 验证当前账号密码并建立登录会话；企业授权由每次请求的明确路径决定。
 */
@Service
public class AuthService {
    private final AuthMapper users;
    private final AuthSessionService sessions;
    private final PasswordEncoder passwords;
    private final AuthContextService identity;
    private final String nonexistentUserHash;
    private final AuthLoginRateLimiter rateLimiter;

    public AuthService(AuthMapper users, AuthSessionService sessions, PasswordEncoder passwords,
                       AuthContextService identity, AuthLoginRateLimiter rateLimiter) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.identity = identity;
        this.rateLimiter = rateLimiter;
        this.nonexistentUserHash = passwords.encode(UUID.randomUUID().toString());
    }

    public BootstrapStatusResponse bootstrapStatus() {
        return new BootstrapStatusResponse(users.superAdminInitialized());
    }

    public void login(ApiLoginRequest value, HttpServletRequest request) {
        rateLimiter.checkSource(request.getRemoteAddr());
        if (value == null || value.identifier() == null || value.identifier().isBlank()
            || value.password() == null || value.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号和密码不能为空");
        }
        if (value.password().codePointCount(0, value.password().length()) > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码输入过长");
        }
        String identifier = IdentityValidation.loginIdentifier(value.identifier());
        UserEntity user = users.findByLoginIdentifier(identifier).orElse(null);
        String account = user == null ? "identifier:" + identifier : "user:" + user.id();
        rateLimiter.checkAccount(account);
        boolean matches = passwords.matches(value.password(), user == null ? nonexistentUserHash : user.passwordHash());
        if (user == null || !matches || !"active".equals(user.status()) || !identity.userAllowed(user)) {
            rateLimiter.failed(account);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        }
        HttpSession session = sessions.establish(request, user);
        // 密码验证期间发生改密或停用时，不能用此前读取的身份建立有效会话。
        identity.requireUser(session);
        try {
            rateLimiter.succeeded(account);
        } catch (RuntimeException exception) {
            session.invalidate();
            throw exception;
        }
    }
}
