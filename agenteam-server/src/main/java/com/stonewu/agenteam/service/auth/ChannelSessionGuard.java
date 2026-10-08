package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 每次请求核对外部登录的本地撤销状态，不能从企业身份获得全局权限。
 */
@Service
public class ChannelSessionGuard {
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;
    private final AuthMapper users;
    private final Clock clock;

    public ChannelSessionGuard(EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings,
                               AuthMapper users, Clock clock) {
        this.connections = connections;
        this.bindings = bindings;
        this.users = users;
        this.clock = clock;
    }

    public static boolean external(HttpSession session) {
        return session != null && "channel".equals(session.getAttribute(AuthSessionAttributes.METHOD));
    }

    public static String enterprise(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(AuthSessionAttributes.RESTRICTED_ENTERPRISE);
        return value instanceof String text ? text : null;
    }

    public boolean valid(HttpSession session, UserEntity user) {
        if (!external(session)) {
            return true;
        }
        if (user.superAdmin() || !(session.getAttribute(AuthSessionAttributes.AUTHENTICATED_AT) instanceof Number time)
            || clock.millis() - time.longValue() >= Duration.ofHours(1).toMillis()) {
            return false;
        }
        String enterprise = enterprise(session);
        Object bindingId = session.getAttribute(AuthSessionAttributes.CHANNEL_BINDING);
        Object connectionId = session.getAttribute(AuthSessionAttributes.CHANNEL_CONNECTION);
        Object version = session.getAttribute(AuthSessionAttributes.CHANNEL_REVISION);
        Object connectionVersion = session.getAttribute(AuthSessionAttributes.CHANNEL_CONNECTION_REVISION);
        if (enterprise == null || !(bindingId instanceof String) || !(connectionId instanceof String)
            || !(version instanceof Number revision) || !(connectionVersion instanceof Number connectionRevision)
            || !users.isActiveMember(user.id(), enterprise)) {
            return false;
        }
        var binding = bindings.selectById((String) bindingId);
        var connection = connections.selectById((String) connectionId);
        return binding != null && connection != null && Objects.equals(binding.getEnterpriseId(), enterprise)
            && Objects.equals(connection.getEnterpriseId(), enterprise) && Objects.equals(binding.getUserId(), user.id())
            && Objects.equals(binding.getConnectionId(), connection.getId()) && "active".equals(binding.getStatus())
            && binding.getRevision() == revision.longValue() && Boolean.TRUE.equals(binding.getExternalLoginEnabled())
            && connection.getRevision() == connectionRevision.longValue()
            && "enabled".equals(connection.getStatus()) && Boolean.TRUE.equals(connection.getLoginEnabled());
    }
}
