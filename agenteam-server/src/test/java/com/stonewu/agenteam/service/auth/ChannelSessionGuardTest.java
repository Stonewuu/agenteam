package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证一小时绝对有效期和每次读取时的本地撤销条件。 */
class ChannelSessionGuardTest {
    private final EnterpriseIntegrationMapper connections = mock(EnterpriseIntegrationMapper.class);
    private final UserChannelBindingMapper bindings = mock(UserChannelBindingMapper.class);
    private final AuthMapper users = mock(AuthMapper.class);
    private final Clock clock = Clock.fixed(AuthSessionSecurityTest.NOW, ZoneOffset.UTC);
    private final ChannelSessionGuard guard = new ChannelSessionGuard(connections, bindings, users, clock);
    private final UserChannelBindingRow binding = new UserChannelBindingRow();
    private final EnterpriseIntegrationRow connection = new EnterpriseIntegrationRow();

    @BeforeEach
    void prepare() {
        binding.setId("binding");
        binding.setEnterpriseId("enterprise-a");
        binding.setConnectionId("connection");
        binding.setUserId("user-one");
        binding.setRevision(1L);
        binding.setStatus("active");
        binding.setExternalLoginEnabled(true);
        connection.setId("connection");
        connection.setEnterpriseId("enterprise-a");
        connection.setStatus("enabled");
        connection.setLoginEnabled(true);
        connection.setRevision(3L);
        when(bindings.selectById("binding")).thenReturn(binding);
        when(connections.selectById("connection")).thenReturn(connection);
        when(users.isActiveMember("user-one", "enterprise-a")).thenReturn(true);
    }

    @Test
    void absoluteExpiryDoesNotDependOnRecentActivity() {
        var user = AuthSessionSecurityTest.user(false, 1, "active");
        var request = new MockHttpServletRequest();
        var session = new AuthSessionService(clock, new RequestForgeryProtection()).establishExternal(request, user, binding, 3);
        assertEquals(1800, session.getMaxInactiveInterval());
        assertTrue(guard.valid(session, user));
        session.setAttribute(AuthSessionAttributes.AUTHENTICATED_AT, clock.millis() - Duration.ofHours(1).toMillis());
        assertFalse(guard.valid(session, user));
    }

    @Test
    void membershipBindingAndConnectionChangesRevokeSession() {
        var user = AuthSessionSecurityTest.user(false, 1, "active");
        var session = new AuthSessionService(clock, new RequestForgeryProtection())
            .establishExternal(new MockHttpServletRequest(), user, binding, 3);
        when(users.isActiveMember("user-one", "enterprise-a")).thenReturn(false);
        assertFalse(guard.valid(session, user));
        when(users.isActiveMember("user-one", "enterprise-a")).thenReturn(true);
        binding.setStatus("revoked");
        assertFalse(guard.valid(session, user));
        binding.setStatus("active");
        binding.setExternalLoginEnabled(false);
        assertFalse(guard.valid(session, user));
        binding.setExternalLoginEnabled(true);
        connection.setLoginEnabled(false);
        assertFalse(guard.valid(session, user));
        connection.setLoginEnabled(true);
        connection.setRevision(4L);
        assertFalse(guard.valid(session, user));
    }

    @Test
    void copiedBindingAndSuperAdministratorNeverPassGuard() {
        var user = AuthSessionSecurityTest.user(false, 1, "active");
        var session = new AuthSessionService(clock, new RequestForgeryProtection())
            .establishExternal(new MockHttpServletRequest(), user, binding, 3);
        assertFalse(guard.valid(session, AuthSessionSecurityTest.user(true, 1, "active")));
        binding.setEnterpriseId("enterprise-b");
        assertFalse(guard.valid(session, user));
    }
}
