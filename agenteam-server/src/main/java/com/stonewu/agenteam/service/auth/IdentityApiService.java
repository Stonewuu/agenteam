package com.stonewu.agenteam.service.auth;

import java.util.List;
import com.stonewu.agenteam.model.auth.request.ApiBootstrapRequest;
import com.stonewu.agenteam.model.auth.request.ApiLoginRequest;
import com.stonewu.agenteam.model.auth.request.PasswordResetConfirmRequest;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 新版全局身份读取与登录，企业业务另按路径验证。
 */
@Service
public class IdentityApiService {
    private final AuthService authentication;
    private final AuthContextService context;
    private final IdentityResponseService views;
    private final AuthBootstrapService bootstrap;
    private final AuthSessionService sessions;
    private final AccountRecoveryService recovery;

    public IdentityApiService(AuthService authentication, AuthContextService context, IdentityResponseService views,
                              AuthBootstrapService bootstrap, AuthSessionService sessions,
                              AccountRecoveryService recovery) {
        this.authentication = authentication;
        this.context = context;
        this.views = views;
        this.bootstrap = bootstrap;
        this.sessions = sessions;
        this.recovery = recovery;
    }

    public CurrentIdentityResponse current(HttpSession session) {
        var current = views.current(context.requireUser(session));
        if (!ChannelSessionGuard.external(session)) {
            return current;
        }
        String enterprise = ChannelSessionGuard.enterprise(session);
        return new CurrentIdentityResponse(current.id(), current.username(), current.displayName(), null, false,
            false, enterprise, current.enterprises().stream().filter(value -> value.id().equals(enterprise)).toList(),
            current.preferences(), current.revision(), "channel", enterprise, List.of());
    }

    public CurrentIdentityResponse login(ApiLoginRequest request, HttpServletRequest servletRequest) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请输入账号和密码。");
        }
        authentication.login(request, servletRequest);
        return current(servletRequest.getSession(false));
    }

    public CurrentIdentityResponse bootstrap(ApiBootstrapRequest request, HttpServletRequest servletRequest) {
        var user = bootstrap.create(request, servletRequest.getRemoteAddr());
        sessions.establish(servletRequest, user);
        return current(servletRequest.getSession(false));
    }

    public void resetPassword(PasswordResetConfirmRequest request, HttpServletRequest servletRequest) {
        recovery.resetPassword(request);
        sessions.anonymousAfterReset(servletRequest);
    }
}
