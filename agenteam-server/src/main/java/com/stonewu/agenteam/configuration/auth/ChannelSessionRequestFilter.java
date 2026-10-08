package com.stonewu.agenteam.configuration.auth;

import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 外部会话只有明确企业路径和必要登录入口可用，新增全局接口默认要求本地认证。
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 60)
public class ChannelSessionRequestFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of("GET /api/v1/auth/me", "GET /api/v1/auth/csrf",
        "GET /api/v1/auth/bootstrap-status", "POST /api/v1/auth/login", "POST /api/v1/auth/logout",
        "POST /api/v1/auth/reauthenticate");
    private final AuthContextService identity;
    private final ApiResponses responses;

    public ChannelSessionRequestFilter(AuthContextService identity, ApiResponses responses) {
        this.identity = identity;
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/")
            || !ChannelSessionGuard.external(request.getSession(false));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!ALLOWED.contains(request.getMethod() + " " + path)) {
                var session = request.getSession(false);
                identity.requireUser(session);
                String base = "/api/v1/enterprises/" + ChannelSessionGuard.enterprise(session);
                if (!path.equals(base) && !path.startsWith(base + "/")) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", "请先验证账号密码，再访问此功能。");
                }
            }
            chain.doFilter(request, response);
        } catch (Exception failure) {
            if (response.isCommitted()) {
                responses.logFailure(failure, request, response.getStatus());
                throw failure;
            }
            responses.writeFailure(failure, request, response);
        }
    }
}
