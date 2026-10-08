package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.auth.IdentityResponseService;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.enterprise.request.InvitationAcceptRequest;
import com.stonewu.agenteam.model.enterprise.request.InvitationCreateRequest;
import com.stonewu.agenteam.model.enterprise.response.InvitationPreview;
import com.stonewu.agenteam.model.enterprise.response.InvitationView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.AuthLoginRateLimiter;
import com.stonewu.agenteam.service.auth.AuthSessionService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 邀请请求的会话与重复提交处理，注册事务提交后才建立新会话。
 */
@Service
public class InvitationApiService {
    private final AuthContextService context;
    private final InvitationManagementService management;
    private final InvitationAcceptanceService acceptance;
    private final IdempotentRequestService requests;
    private final AuthSessionService sessions;
    private final IdentityResponseService views;
    private final AuthLoginRateLimiter limiter;

    public InvitationApiService(AuthContextService context, InvitationManagementService management,
                                InvitationAcceptanceService acceptance,
                                IdempotentRequestService requests, AuthSessionService sessions,
                                IdentityResponseService views, AuthLoginRateLimiter limiter) {
        this.context = context;
        this.management = management;
        this.acceptance = acceptance;
        this.requests = requests;
        this.sessions = sessions;
        this.views = views;
        this.limiter = limiter;
    }

    public PageResponse<InvitationView> list(String enterpriseId, String cursor, Integer limit, String query,
                                             HttpServletRequest request) {
        return management.list(context.requireEnterprise(request.getSession(false), enterpriseId), cursor, limit,
            query);
    }

    public ApiOperationResult create(String enterpriseId, InvitationCreateRequest payload, HttpServletRequest request) {
        var actor = context.requireEnterprise(request.getSession(false), enterpriseId);
        return requests.execute(request, actor.user(), enterpriseId, Set.of("/roleIds", "/teamIds"),
            () -> management.authorize(actor),
            () -> ApiOperationResult.of(201, management.create(actor, payload)));
    }

    public ApiOperationResult revise(String enterpriseId, String id, boolean resend, HttpServletRequest request) {
        var actor = context.requireEnterprise(request.getSession(false), enterpriseId);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterpriseId, Set.of(), () -> management.authorize(actor),
            () -> ApiOperationResult.of(resend ? 202 : 200,
                resend ? management.resend(actor, id, revision) : management.revoke(actor, id, revision)));
    }

    public InvitationPreview preview(String token, HttpServletRequest request) {
        limiter.checkSource("invitation-preview:" + request.getRemoteAddr());
        return acceptance.preview(token, context.optionalUser(request.getSession(false)));
    }

    public CurrentIdentityResponse accept(InvitationAcceptRequest payload, HttpServletRequest request) {
        limiter.checkSource("invitation-accept:" + request.getRemoteAddr());
        var accepted = acceptance.accept(payload, context.optionalUser(request.getSession(false)));
        if (accepted.createdAccount()) {
            sessions.establish(request, accepted.user());
        }
        return views.current(context.requireUser(request.getSession(false)));
    }
}
