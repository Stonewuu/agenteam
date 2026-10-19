package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.configuration.auth.OperationActorContext;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.SessionIdentityMapper;
import com.stonewu.agenteam.model.auth.entity.ImpersonationState;
import com.stonewu.agenteam.model.auth.entity.OperationActor;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Set;
import java.util.function.Function;

/** 普通请求和长连接共用认证来源、有效用户及身份版本校验。 */
@Service
public class SessionIdentityService {
    private final AuthMapper users;
    private final SessionIdentityMapper revisions;
    private final AccountBehaviorService behavior;
    private final ChannelSessionGuard channels;
    private final RequestForgeryProtection protection;
    private final Clock clock;

    public SessionIdentityService(AuthMapper users, SessionIdentityMapper revisions, AccountBehaviorService behavior,
                                  ChannelSessionGuard channels, RequestForgeryProtection protection, Clock clock) {
        this.users = users;
        this.revisions = revisions;
        this.behavior = behavior;
        this.channels = channels;
        this.protection = protection;
        this.clock = clock;
    }

    public UserEntity authenticated(String sessionId, Function<String, Object> attributes) {
        Object id = attributes.apply(AuthSessionAttributes.USER_ID);
        Object version = attributes.apply(AuthSessionAttributes.SESSION_VERSION);
        Object time = attributes.apply(AuthSessionAttributes.AUTHENTICATED_AT);
        long now = clock.millis();
        if (!(id instanceof String userId) || !(version instanceof Number number)
            || !(time instanceof Number timestamp) || timestamp.longValue() < 0 || timestamp.longValue() > now
            || now - timestamp.longValue() >= Duration.ofDays(7).toMillis()) {
            throw expired();
        }
        UserEntity user = users.findById(userId).orElseThrow(SessionIdentityService::expired);
        if (!"active".equals(user.status()) || !behavior.allowed(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "用户已被停用。");
        }
        if (user.sessionVersion() != number.longValue() || !channels.valid(attributes, user)) {
            throw expired();
        }
        if (!revisions.matches(root(sessionId, attributes), revision(sessionId, attributes))) {
            throw changed();
        }
        if (state(attributes) != null && (!user.superAdmin()
            || !"password".equals(attributes.apply(AuthSessionAttributes.METHOD)))) {
            throw expired();
        }
        return user;
    }

    public UserEntity effective(String sessionId, Function<String, Object> attributes) {
        UserEntity authenticated = authenticated(sessionId, attributes);
        ImpersonationState state = state(attributes);
        if (state == null) {
            return authenticated;
        }
        UserEntity target = users.findById(state.userId()).orElseThrow(SessionIdentityService::targetUnavailable);
        if (!"active".equals(target.status()) || !behavior.allowed(target)
            || target.sessionVersion() != state.sessionVersion()) {
            throw targetUnavailable();
        }
        OperationActorContext.set(new OperationActor(authenticated.id(), target.id(), state.id()));
        return target;
    }

    public UserEntity authenticated(HttpSession session) {
        if (session == null) {
            throw expired();
        }
        return authenticated(session.getId(), session::getAttribute);
    }

    public UserEntity effective(HttpSession session) {
        if (session == null) {
            throw expired();
        }
        return effective(session.getId(), session::getAttribute);
    }

    public static ImpersonationState state(Function<String, Object> attributes) {
        return attributes.apply(AuthSessionAttributes.IMPERSONATION) instanceof ImpersonationState state ? state : null;
    }

    public static ImpersonationState state(HttpSession session) {
        return session == null ? null : state(session::getAttribute);
    }

    public static String revision(HttpSession session) {
        return session == null ? null : revision(session.getId(), session::getAttribute);
    }

    private static String revision(String id, Function<String, Object> attributes) {
        return attributes.apply(AuthSessionAttributes.IDENTITY_REVISION) instanceof String value ? value : root(id, attributes);
    }

    private static String root(String id, Function<String, Object> attributes) {
        if (attributes.apply(AuthSessionAttributes.IDENTITY_ROOT) instanceof String value) {
            return value;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境缺少会话摘要算法", exception);
        }
    }

    public void switchTo(HttpServletRequest request, ImpersonationState state) {
        HttpSession session = request.getSession(false);
        authenticated(session);
        String root = root(session.getId(), session::getAttribute);
        String next = UUID.randomUUID().toString();
        if (!revisions.advance(root, revision(session), next)) {
            throw changed();
        }
        session.setAttribute(AuthSessionAttributes.IDENTITY_ROOT, root);
        session.setAttribute(AuthSessionAttributes.IDENTITY_REVISION, next);
        if (state == null) {
            session.removeAttribute(AuthSessionAttributes.IMPERSONATION);
        } else {
            session.setAttribute(AuthSessionAttributes.IMPERSONATION, state);
        }
        request.changeSessionId();
        protection.rotate(session);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        session.invalidate();
                    }
                }
            });
        }
    }

    public void verifyRequest(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(AuthSessionAttributes.USER_ID) == null) {
            return;
        }
        String submitted = request.getHeader("X-Identity-Revision");
        boolean write = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        if ((submitted != null && !submitted.equals(revision(session)))
            || (write && session.getAttribute(AuthSessionAttributes.IDENTITY_REVISION) != null && submitted == null)) {
            throw changed();
        }
        authenticated(session);
    }

    public static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "IDENTITY_CHANGED", "登录身份已变化，请重新加载页面后操作。");
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已失效，请重新登录。");
    }

    private static ApiException targetUnavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "IMPERSONATION_TARGET_UNAVAILABLE",
            "当前用户已无法访问，请返回管理员身份。");
    }
}
