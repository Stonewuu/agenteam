package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.AuthTokenMapper;
import com.stonewu.agenteam.mapper.auth.PasswordResetSourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthToken;
import com.stonewu.agenteam.model.auth.request.PasswordResetConfirmRequest;
import com.stonewu.agenteam.model.auth.request.PasswordResetRequest;
import com.stonewu.agenteam.model.auth.request.TokenRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.mail.MailQueueService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 邮箱确认与密码找回；一次性凭据、账户变更和审计共同提交。
 */
@Service
public class AccountRecoveryService {
    private final AccountBehaviorService behavior;
    private final AuthMapper users;
    private final AuthTokenMapper tokens;
    private final SingleUseTokenGenerator generator;
    private final PasswordResetSourceMapper sources;
    private final MailQueueService mail;
    private final PasswordEncoder encoder;
    private final AuthLoginRateLimiter limiter;
    private final AuditEventService audit;
    private final Clock clock;

    public AccountRecoveryService(AuthMapper users, AuthTokenMapper tokens, SingleUseTokenGenerator generator,
                                  PasswordResetSourceMapper sources,
                                  MailQueueService mail, PasswordEncoder encoder, AuthLoginRateLimiter limiter,
                                  AuditEventService audit, Clock clock, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.users = users;
        this.tokens = tokens;
        this.generator = generator;
        this.sources = sources;
        this.mail = mail;
        this.encoder = encoder;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
    }

    public void requestPasswordReset(PasswordResetRequest request, String source) {
        if (request == null) {
            throw ApiException.invalidField("identifier", "请输入账号或邮箱。");
        }
        String identifier = IdentityValidation.loginIdentifier(request.identifier());
        try {
            long wait = sources.check(source);
            if (wait > 0) {
                throw new LoginRateLimitException(wait);
            }
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "暂时无法申请重置，请稍后重试。");
        }
        mail.enqueuePasswordResetLookup(identifier);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resetPassword(PasswordResetConfirmRequest request) {
        if (request == null) {
            throw invalidToken();
        }
        IdentityValidation.newPassword(request.newPassword());
        AuthToken original = token(request.token(), "password_reset");
        UserEntity user = lockUser(original.userId());
        AuthToken current = tokens.findLocked(original.id()).orElseThrow(this::invalidToken);
        Instant now = clock.instant();
        if (current.consumedAt() != null || !current.expiresAt().isAfter(now) || current.targetEmail() == null
            || user.emailVerifiedAt() == null || !current.targetEmail().equalsIgnoreCase(user.email())) {
            throw invalidToken();
        }
        if (!tokens.consume(current.id(), now)) {
            throw invalidToken();
        }
        if (!users.replacePassword(user.id(), user.passwordHash(), user.sessionVersion(),
            encoder.encode(request.newPassword()), now)) {
            throw invalidToken();
        }
        users.invalidateUnusedTokens(user.id(), now);
        limiter.succeeded("user:" + user.id());
        audit.record(null, user, "auth.password.reset", "user", user.id(), "通过邮件重置密码并撤销旧登录会话",
            Map.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verifyEmail(TokenRequest request) {
        if (request == null) {
            throw invalidToken();
        }
        AuthToken original = token(request.token(), "email_verify");
        UserEntity user = lockUser(original.userId());
        AuthToken current = tokens.findLocked(original.id()).orElseThrow(this::invalidToken);
        if (current.consumedAt() != null) {
            if (user.emailVerifiedAt() != null && current.targetEmail() != null && current.targetEmail()
                .equalsIgnoreCase(user.email())) {
                return;
            }
            throw invalidToken();
        }
        Instant now = clock.instant();
        if (!current.expiresAt().isAfter(now) || current.targetEmail() == null) {
            throw invalidToken();
        }
        try {
            if (!users.updateVerifiedEmail(user.id(), current.targetEmail(), now)) {
                throw invalidToken();
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_UNAVAILABLE", "该邮箱已绑定其他账号，请使用另一个邮箱。");
        }
        if (!tokens.consume(current.id(), now)) {
            throw invalidToken();
        }
        users.invalidateUnusedTokens(user.id(), now);
        audit.record(null, user, "auth.email.verify", "user", user.id(), "完成账号邮箱验证", Map.of());
    }

    private AuthToken token(String value, String purpose) {
        String hash = generator.hash(value);
        if (hash.isEmpty()) {
            throw invalidToken();
        }
        AuthToken token = tokens.findByHash(hash).orElseThrow(this::invalidToken);
        if (!token.purpose().equals(purpose)) {
            throw invalidToken();
        }
        return token;
    }

    private UserEntity lockUser(String userId) {
        UserEntity user = users.findByIdForUpdate(userId).orElseThrow(this::invalidToken);
        behavior.requireOperation(user.id(), AccountOperation.RESET_PASSWORD);
        if (!user.status().equals("active")) {
            throw invalidToken();
        }
        return user;
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH_TOKEN_INVALID",
            "链接已失效或已使用，请重新申请。");
    }
}
