package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.OperationActorContext;
import com.stonewu.agenteam.configuration.auth.AuthSessionAttributes;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.model.auth.entity.ImpersonationState;
import com.stonewu.agenteam.model.auth.entity.OperationActor;
import com.stonewu.agenteam.model.auth.request.ImpersonationStartRequest;
import com.stonewu.agenteam.model.auth.response.ImpersonationStatus;
import com.stonewu.agenteam.model.auth.response.ImpersonationView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** 管理员只能代入一个有效账号，恢复入口只验证原管理员。 */
@Service
public class ImpersonationService {
    private final SessionIdentityService identity;
    private final AuthContextService context;
    private final AuthMapper users;
    private final AccountBehaviorService behavior;
    private final AuditEventService audit;
    private final Clock clock;

    public ImpersonationService(SessionIdentityService identity, AuthContextService context, AuthMapper users,
                                AccountBehaviorService behavior, AuditEventService audit, Clock clock) {
        this.identity = identity;
        this.context = context;
        this.users = users;
        this.behavior = behavior;
        this.audit = audit;
        this.clock = clock;
    }

    public ImpersonationStatus status(HttpSession session) {
        UserEntity administrator = identity.authenticated(session);
        return new ImpersonationStatus(SessionIdentityService.revision(session), view(session, administrator));
    }

    public ImpersonationView view(HttpSession session, UserEntity administrator) {
        ImpersonationState state = SessionIdentityService.state(session);
        return state == null ? null : new ImpersonationView(state.id(), state.userId(), state.displayName(),
            state.username(), administrator.displayName(), state.enterpriseId(), state.returnPath());
    }

    @Transactional
    public ImpersonationStatus start(ImpersonationStartRequest input, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        UserEntity administrator = identity.authenticated(session);
        if (!administrator.superAdmin() || !"password".equals(session.getAttribute(AuthSessionAttributes.METHOD))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED", "只有系统超级管理员可以切换用户身份。");
        }
        if (SessionIdentityService.state(session) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPERSONATION_ACTIVE", "请先返回管理员身份，再切换其他用户。");
        }
        if (input == null || input.userId() == null || input.enterpriseId() == null
            || input.userId().equals(administrator.id())) {
            throw ApiException.invalidField("userId", "请选择其他有效用户。");
        }
        context.requirePermission(session, input.enterpriseId(), "enterprise.members.view");
        UserEntity target = users.findById(input.userId()).orElseThrow(ImpersonationService::unavailable);
        if (!"active".equals(target.status()) || !behavior.allowed(target)
            || !behavior.enterpriseAllowed(target, input.enterpriseId())
            || !users.isActiveMember(target.id(), input.enterpriseId())) {
            throw unavailable();
        }
        String returnPath = input.returnPath() == null ? "/organization/members" : input.returnPath();
        if (returnPath.length() > 2048 || !returnPath.matches("/organization/members(?:\\?[^#\\r\\n\\\\]*)?")) {
            throw ApiException.invalidField("returnPath", "返回位置无效，请重新打开成员列表。");
        }
        var state = new ImpersonationState(UUID.randomUUID().toString(), target.id(), target.sessionVersion(),
            target.displayName(), target.username(), input.enterpriseId(), returnPath, clock.millis());
        // 审计写入失败时不切换；会话切换失败则本事务不提交该记录。
        audit.record(input.enterpriseId(), administrator, "auth.impersonation.start", "user", target.id(),
            "以用户身份登录", Map.of("impersonationId", state.id(), "effectiveUserId", target.id()));
        identity.switchTo(request, state);
        return status(session);
    }

    @Transactional
    public ImpersonationStatus stop(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        UserEntity administrator = identity.authenticated(session);
        ImpersonationState state = SessionIdentityService.state(session);
        if (state == null) {
            return status(session);
        }
        OperationActorContext.set(new OperationActor(administrator.id(), administrator.id(), state.id()));
        audit.record(null, administrator, "auth.impersonation.stop", "user", state.userId(),
            "返回管理员身份", Map.of("impersonationId", state.id(), "effectiveUserId", state.userId(),
                "enterpriseId", state.enterpriseId()));
        identity.switchTo(request, null);
        return status(session);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "IMPERSONATION_TARGET_UNAVAILABLE", "当前用户无法登录此企业。");
    }
}
