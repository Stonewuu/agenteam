package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AccountPasswordEncoder;
import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.request.ApiLoginRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthLoginSecurityTest {

    private final AuthMapper accounts = mock(AuthMapper.class);
    private final PermissionMapper permissions = mock(PermissionMapper.class);
    private final AccountPasswordEncoder encoder = new AccountPasswordEncoder();
    private final Clock clock = Clock.fixed(AuthSessionSecurityTest.NOW, ZoneOffset.UTC);
    private final AuthContextService context = new AuthContextService(accounts, permissions, clock,
        new AccountBehaviorService(List.of()), mock(ChannelSessionGuard.class),
        mock(EnterpriseEditionPolicy.class));
    private final AuthLoginRateLimiter rateLimiter = mock(AuthLoginRateLimiter.class);
    private final AuthService service = new AuthService(accounts, new AuthSessionService(clock, new RequestForgeryProtection()), encoder, context, rateLimiter);

    @Test
    void successfulLoginRotatesSessionBeforeGrantingIdentity() {
        String hash = encoder.encode("actual-long-password");
        var user = user(hash);
        availableUser(user);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("old-account-state", "previous-user");
        String previousSessionId = request.getSession().getId();
        service.login(new ApiLoginRequest(" ALICE ", "actual-long-password"), request);
        assertEquals("user-one", context.requireUser(request.getSession()).id());
        assertNotEquals(previousSessionId, request.getSession().getId());
        assertNull(request.getSession().getAttribute("old-account-state"));
        assertEquals(1L, request.getSession().getAttribute(AuthSessionAttributes.SESSION_VERSION));
        assertEquals(clock.millis(), request.getSession().getAttribute(AuthSessionAttributes.AUTHENTICATED_AT));
        assertEquals(43200, request.getSession().getMaxInactiveInterval());
        verify(rateLimiter).checkAccount("user:user-one");
        verify(rateLimiter).succeeded("user:user-one");
    }

    @Test
    void wrongPasswordAndUnknownAccountHaveSamePublicFailure() {
        availableUser(user(encoder.encode("actual-long-password")));
        var wrong = assertThrows(ResponseStatusException.class,
            () -> service.login(new ApiLoginRequest("alice", "wrong-password"), new MockHttpServletRequest()));
        var unknown = assertThrows(ResponseStatusException.class,
            () -> service.login(new ApiLoginRequest("missing", "wrong-password"), new MockHttpServletRequest()));
        assertEquals(wrong.getStatusCode(), unknown.getStatusCode());
        assertEquals(wrong.getReason(), unknown.getReason());
    }

    @Test
    void concurrentCredentialChangeCannotGrantTheNewSessionVersionToOldCredentials() {
        availableUser(user(encoder.encode("actual-long-password")));
        when(accounts.findById("user-one")).thenReturn(Optional.of(AuthSessionSecurityTest.user(false, 2, "active")));
        var request = new MockHttpServletRequest();
        assertEquals(401, assertThrows(ResponseStatusException.class,
            () -> service.login(new ApiLoginRequest("alice", "actual-long-password"), request)).getStatusCode().value());
        assertNull(request.getSession(false));
    }

    private void availableUser(UserEntity user) {
        when(accounts.findByLoginIdentifier("alice")).thenReturn(Optional.of(user));
        when(accounts.findById("user-one")).thenReturn(Optional.of(user));
    }

    private UserEntity user(String hash) {
        return new UserEntity("user-one", "Alice", hash, "成员", "active", false, "enterprise-a",
            null, null, 1, null, 1, clock.instant(), clock.instant());
    }
}
