package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;


/**
 * 长连接直接重读服务端会话，退出登录后不能继续使用连接建立时保留的会话对象。
 */
@Service
public class ConnectionAuthorization {
    private final SessionRepository<? extends Session> sessions;
    private final EnterpriseAuthorizationService authorization;
    private final SessionIdentityService identity;

    public ConnectionAuthorization(SessionRepository<? extends Session> sessions,
                                   EnterpriseAuthorizationService authorization, SessionIdentityService identity) {
        this.sessions = sessions;
        this.authorization = authorization;
        this.identity = identity;
    }

    public boolean allowed(String sessionId, AuthContext actor, String operation) {
        try {
            var current = sessions.findById(sessionId);
            if (current == null || current.isExpired()) {
                return false;
            }
            var effective = identity.effective(current.getId(), current::getAttribute);
            if (!actor.userId().equals(effective.id()) || actor.user().sessionVersion() != effective.sessionVersion()) {
                return false;
            }
            authorization.require(actor, operation);
            return true;
        } catch (RuntimeException exception) {
            // 无法确认当前身份时关闭连接，客户端恢复连接前必须重新通过请求认证。
            return false;
        }
    }
}
