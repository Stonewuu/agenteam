package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionSecurityTest {

    static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private final AuthMapper accounts = mock(AuthMapper.class);
    private final PermissionMapper permissions = mock(PermissionMapper.class);
    private final AuthContextService context = new AuthContextService(accounts, permissions, Clock.fixed(NOW, ZoneOffset.UTC),
        new AccountBehaviorService(List.of()), mock(ChannelSessionGuard.class),
        mock(EnterpriseEditionPolicy.class));

    @Test
    void explicitEnterpriseNeverUsesAnotherTabsPreference() {
        Instant authenticatedAt = NOW.minus(Duration.ofDays(6));
        var session = session(authenticatedAt, 1);
        when(accounts.findById("user-one")).thenReturn(Optional.of(user(false, 1, "active")));
        when(accounts.isActiveMember("user-one", "enterprise-b")).thenReturn(true);
        when(permissions.listPermissionCodes("user-one", "enterprise-b")).thenReturn(List.of("workspace.view"));
        assertEquals("enterprise-b", context.requirePermission(session, "enterprise-b", "workspace.view").enterpriseId());
        assertEquals("enterprise-b", context.requireEnterprise(session, "enterprise-b").enterpriseId());
        assertEquals(authenticatedAt.toEpochMilli(), session.getAttribute(AuthSessionAttributes.AUTHENTICATED_AT));
        verify(permissions, never()).listPermissionCodes("user-one", "enterprise-a");
    }

    @Test
    void rejectsExactlySevenDaysEvenWhenSessionIsStillActive() {
        var session = session(NOW.minus(Duration.ofDays(7)), 1);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> context.requireUser(session)).getStatusCode().value());
        assertTrue(session.isInvalid());
        verify(accounts, never()).findById("user-one");
    }

    @Test
    void changedVersionImmediatelyRevokesOldSession() {
        var session = session(NOW.minusSeconds(60), 1);
        when(accounts.findById("user-one")).thenReturn(Optional.of(user(false, 2, "active")));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> context.requireUser(session)).getStatusCode().value());
        assertTrue(session.isInvalid());
    }

    @Test
    void disabledAccountInvalidatesSession() {
        var session = session(NOW, 1);
        when(accounts.findById("user-one")).thenReturn(Optional.of(user(false, 1, "disabled")));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> context.requireUser(session)).getStatusCode().value());
        assertTrue(session.isInvalid());
    }

    @Test
    void superAdminStillNeedsMembershipAndOperationPermission() {
        var session = session(NOW, 1);
        when(accounts.findById("user-one")).thenReturn(Optional.of(user(true, 1, "active")));
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> context.requireEnterprise(session, "enterprise-b")).getStatusCode().value());
        when(accounts.isActiveMember("user-one", "enterprise-b")).thenReturn(true);
        when(permissions.listPermissionCodes("user-one", "enterprise-b")).thenReturn(List.of());
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> context.requirePermission(session, "enterprise-b", "agent.run")).getStatusCode().value());
    }

    @Test
    void sessionsMissingAccountVersionMustAuthenticateAgain() {
        var session = session(NOW, 1);
        session.removeAttribute(AuthSessionAttributes.SESSION_VERSION);
        assertThrows(ResponseStatusException.class, () -> context.requireUser(session));
        assertTrue(session.isInvalid());
    }

    static UserEntity user(boolean superAdmin, long version, String status) {
        return new UserEntity("user-one", "Alice", "stored-hash", "成员", status, superAdmin,
            "enterprise-a", null, null, version, null, 1, NOW, NOW);
    }

    static MockHttpSession session(Instant authenticatedAt, long version) {
        var session = new MockHttpSession();
        session.setAttribute(AuthSessionAttributes.USER_ID, "user-one");
        session.setAttribute(AuthSessionAttributes.SESSION_VERSION, version);
        session.setAttribute(AuthSessionAttributes.AUTHENTICATED_AT, authenticatedAt.toEpochMilli());
        return session;
    }
}
