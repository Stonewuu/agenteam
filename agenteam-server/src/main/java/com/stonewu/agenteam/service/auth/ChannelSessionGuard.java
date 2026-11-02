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
import java.util.function.Function;

/**
 * 每次请求核对绑定、接入和账号权限；管理员权限只来自已绑定的本地账号。
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

    public static boolean restricted(HttpSession session) {
        return external(session) && !Boolean.TRUE.equals(session.getAttribute(AuthSessionAttributes.CHANNEL_ADMINISTRATOR));
    }

    public static String enterprise(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(AuthSessionAttributes.RESTRICTED_ENTERPRISE);
        return value instanceof String text ? text : null;
    }

    public boolean valid(HttpSession session, UserEntity user) {
        return valid(session::getAttribute, user);
    }

    public boolean valid(Function<String, Object> attributes, UserEntity user) {
        if (!"channel".equals(attributes.apply(AuthSessionAttributes.METHOD))) {
            return true;
        }
        if (Boolean.TRUE.equals(attributes.apply(AuthSessionAttributes.CHANNEL_ADMINISTRATOR)) != user.superAdmin()
            || !(attributes.apply(AuthSessionAttributes.AUTHENTICATED_AT) instanceof Number time)
            || clock.millis() - time.longValue() >= Duration.ofHours(1).toMillis()) {
            return false;
        }
        String enterprise = attributes.apply(AuthSessionAttributes.RESTRICTED_ENTERPRISE) instanceof String value ? value : null;
        Object bindingId = attributes.apply(AuthSessionAttributes.CHANNEL_BINDING);
        Object connectionId = attributes.apply(AuthSessionAttributes.CHANNEL_CONNECTION);
        Object version = attributes.apply(AuthSessionAttributes.CHANNEL_REVISION);
        Object connectionVersion = attributes.apply(AuthSessionAttributes.CHANNEL_CONNECTION_REVISION);
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
