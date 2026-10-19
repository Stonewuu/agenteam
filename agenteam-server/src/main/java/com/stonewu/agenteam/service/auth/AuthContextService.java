package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.Objects;

/**
 * 从 HttpSession 解析当前用户和企业，并执行接口权限检查。
 */
@Service
public class AuthContextService {

    private final AuthMapper authMapper;
    private final PermissionMapper permissionMapper;
    private final AccountBehaviorService behavior;
    private final SessionIdentityService sessions;
    private final EnterpriseEditionPolicy edition;

    public AuthContextService(AuthMapper authMapper, PermissionMapper permissionMapper,
                              AccountBehaviorService behavior, SessionIdentityService sessions, EnterpriseEditionPolicy edition) {
        this.authMapper = authMapper;
        this.permissionMapper = permissionMapper;
        this.behavior = behavior;
        this.sessions = sessions;
        this.edition = edition;
    }

    public UserEntity requireUser(HttpSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            return sessions.effective(session);
        } catch (ApiException failure) {
            if (Set.of("SESSION_EXPIRED", "ACCOUNT_UNAVAILABLE").contains(failure.code())) {
                session.invalidate();
            }
            throw failure;
        }
    }

    public UserEntity optionalUser(HttpSession session) {
        if (session == null || stringAttribute(session, AuthSessionAttributes.USER_ID) == null) {
            return null;
        }
        return requireUser(session);
    }

    /**
     * 新接口按路径验证企业，不读取或更改另一个标签页的企业偏好。
     */
    public AuthContext requireEnterprise(HttpSession session, String enterpriseId) {
        UserEntity user = requireUser(session);
        if (ChannelSessionGuard.external(session) && !Objects.equals(enterpriseId, ChannelSessionGuard.enterprise(session))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENTERPRISE_UNAVAILABLE", "当前企业无法访问。");
        }
        if (!behavior.enterpriseAllowed(user, enterpriseId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENTERPRISE_UNAVAILABLE", "当前企业无法访问。");
        }
        if (enterpriseId == null || enterpriseId.isBlank() || !authMapper.isActiveMember(user.id(), enterpriseId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENTERPRISE_UNAVAILABLE",
                "当前企业无法访问，请选择其他企业或联系管理员。");
        }
        edition.requireEnterpriseScope(enterpriseId);
        var permissions = permissionMapper.listPermissionCodes(user.id(), enterpriseId).stream()
            .filter(code -> behavior.permissionAllowed(user, code)).toList();
        return new AuthContext(user, enterpriseId, Set.copyOf(permissions));
    }

    public boolean userAllowed(UserEntity user) {
        return behavior.allowed(user);
    }

    public AuthContext requirePermission(HttpSession session, String enterpriseId, String permission) {
        AuthContext context = requireEnterprise(session, enterpriseId);
        if (!context.permissions().contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有执行此操作的权限");
        }
        return context;
    }

    private static String stringAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
