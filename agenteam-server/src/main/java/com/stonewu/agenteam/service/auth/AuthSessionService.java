package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collections;

/**
 * 完成重新认证后更换会话编号、清除旧身份属性并更新防伪令牌。
 */
@Service
public class AuthSessionService {
    private final Clock clock;
    private final RequestForgeryProtection protection;

    public AuthSessionService(Clock clock, RequestForgeryProtection protection) {
        this.clock = clock;
        this.protection = protection;
    }

    public HttpSession establish(HttpServletRequest request, UserEntity user) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        Collections.list(session.getAttributeNames()).forEach(session::removeAttribute);
        session.setAttribute(AuthSessionAttributes.USER_ID, user.id());
        session.setAttribute(AuthSessionAttributes.SESSION_VERSION, user.sessionVersion());
        session.setAttribute(AuthSessionAttributes.AUTHENTICATED_AT, clock.millis());
        session.setAttribute(AuthSessionAttributes.METHOD, "password");
        session.setMaxInactiveInterval(12 * 60 * 60);
        protection.rotate(session);
        return session;
    }

    public HttpSession establishExternal(HttpServletRequest request, UserEntity user, UserChannelBindingRow binding, long connectionRevision) {
        HttpSession session = establish(request, user);
        session.setAttribute(AuthSessionAttributes.METHOD, "channel");
        session.setAttribute(AuthSessionAttributes.RESTRICTED_ENTERPRISE, binding.getEnterpriseId());
        session.setAttribute(AuthSessionAttributes.CHANNEL_BINDING, binding.getId());
        session.setAttribute(AuthSessionAttributes.CHANNEL_CONNECTION, binding.getConnectionId());
        session.setAttribute(AuthSessionAttributes.CHANNEL_REVISION, binding.getRevision());
        session.setAttribute(AuthSessionAttributes.CHANNEL_CONNECTION_REVISION, connectionRevision);
        session.setMaxInactiveInterval(30 * 60);
        return session;
    }

    public void anonymousAfterReset(HttpServletRequest request) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession anonymous = request.getSession();
        anonymous.setMaxInactiveInterval(12 * 60 * 60);
        protection.rotate(anonymous);
    }
}
