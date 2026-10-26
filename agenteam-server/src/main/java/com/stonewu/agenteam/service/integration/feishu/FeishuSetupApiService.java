package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.integration.request.FeishuSetupRequest;
import com.stonewu.agenteam.model.integration.response.FeishuManagerView;
import com.stonewu.agenteam.model.integration.response.FeishuSetupView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.auth.SessionIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 企业和系统入口共享流程，读取创建入口时仍检查当前用户与任务归属。 */
@Service
public class FeishuSetupApiService {
    public record Overview(List<FeishuManagerView> managers, List<FeishuSetupView> jobs) {
    }
    public record Action(Long expectedRevision) {
    }
    public record Empty() {
    }
    private final AuthContextService identity;
    private final IntegrationManagementPolicy policy;
    private final IdempotentRequestService requests;
    private final FeishuSetupStore store;
    private final FeishuManagementGrantStore grants;
    private final FeishuManagementAuthorizationService authorization;
    private final FeishuQrCode qr;
    private final FeishuManagementConnections managementConnections;

    public FeishuSetupApiService(AuthContextService identity, IntegrationManagementPolicy policy, IdempotentRequestService requests,
            FeishuSetupStore store, FeishuManagementGrantStore grants, FeishuManagementAuthorizationService authorization, FeishuQrCode qr,
            FeishuManagementConnections managementConnections) {
        this.identity = identity;
        this.policy = policy;
        this.requests = requests;
        this.store = store;
        this.grants = grants;
        this.authorization = authorization;
        this.qr = qr;
        this.managementConnections = managementConnections;
    }

    public Overview overview(String enterprise, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        var userGrants = grants.list(enterprise, actor.id());
        var verified = managementConnections.verified(enterprise);
        var managers = store.managers(enterprise).stream().map(manager -> new FeishuManagerView(manager.getId(), manager.getName(),
            manager.getExternalTenantId(), grants.usable(userGrants.get(manager.getId()), manager), manager.getPurpose(),
            "enabled".equals(manager.getStatus()) && manager.getIdentityVerifiedAt() != null
                && ("app_management".equals(manager.getPurpose()) || manager.getCredentialRevision().equals(verified.get(manager.getId()))),
            Boolean.TRUE.equals(manager.getLoginEnabled()), Boolean.TRUE.equals(manager.getMessagingEnabled()), Boolean.TRUE.equals(manager.getBindingEnabled()))).toList();
        return new Overview(managers, store.list(enterprise, actor.id()));
    }

    public ApiOperationResult create(String enterprise, FeishuSetupRequest input, HttpServletRequest request) {
        InputValidation.request(request, FeishuSetupRequest.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(202, store.create(actor, enterprise, system(request), input)));
    }

    public FeishuSetupView get(String enterprise, String id, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        return store.view(store.require(enterprise, actor.id(), id));
    }

    public ApiOperationResult change(String enterprise, String id, Action input, boolean retry, HttpServletRequest request) {
        InputValidation.request(request, Action.class);
        if (input.expectedRevision() == null || input.expectedRevision() < 1) {
            throw ApiException.invalidField("expectedRevision", "请刷新创建进度后重试。");
        }
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200,
            store.change(actor, enterprise, system(request), id, input.expectedRevision(), retry)));
    }

    public URI open(String enterprise, String id, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        var row = store.require(enterprise, actor.id(), id);
        if (!store.view(row).canOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "FEISHU_CREATION_LINK_EXPIRED", "创建入口已结束，请返回群策查看当前进度。");
        }
        return FeishuAppClient.allowedLink(store.material(row).url());
    }

    public byte[] qr(String enterprise, String id, HttpServletRequest request) {
        return qr.render(open(enterprise, id, request).toString());
    }

    public ApiOperationResult authorize(String enterprise, String connection, HttpServletRequest request) {
        InputValidation.request(request, Empty.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200,
            Map.of("authorizationUrl", authorization.start(actor, enterprise, system(request), connection, request).toString())));
    }

    public ApiOperationResult disconnect(String enterprise, String connection, HttpServletRequest request) {
        InputValidation.request(request, Empty.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> {
            store.manager(enterprise, connection);
            var grant = grants.find(enterprise, connection, actor.id());
            if (grant != null) {
                grants.invalidate(grant, true);
            }
            return ApiOperationResult.of(200, Map.of("disconnected", true));
        });
    }

    private ApiOperationResult execute(UserEntity actor, String enterprise, HttpServletRequest request, Supplier<ApiOperationResult> action) {
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.manage", true), action);
    }

    private UserEntity actor(String enterprise, HttpServletRequest request) {
        if (SessionIdentityService.state(request.getSession(false)) != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERSONAL_AUTH_REQUIRED", "请结束代操作，使用本人账号创建飞书机器人。");
        }
        if (ChannelSessionGuard.external(request.getSession(false))) {
            if (system(request)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", "请先验证账号密码，再访问平台管理。");
            }
            identity.requireEnterprise(request.getSession(false), enterprise);
        }
        var actor = identity.requireUser(request.getSession(false));
        policy.require(actor, enterprise, system(request), "integration.manage", false);
        return actor;
    }

    private boolean system(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/system/");
    }
}
