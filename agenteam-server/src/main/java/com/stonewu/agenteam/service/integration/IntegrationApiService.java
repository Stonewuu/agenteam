package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationSecretRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationStatusRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationUpdateRequest;
import com.stonewu.agenteam.model.integration.response.IntegrationView;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 企业和全局管理入口共用业务实现，管理身份只从实际路由与当前会话取得。
 */
@Service
public class IntegrationApiService {
    private final AuthContextService identity;
    private final IntegrationManagementPolicy policy;
    private final IntegrationManagementService management;
    private final IntegrationQueryService queries;
    private final IntegrationCheckService checks;
    private final IdempotentRequestService requests;
    private final IntegrationProviderRegistry providers;

    public IntegrationApiService(AuthContextService identity, IntegrationManagementPolicy policy,
                                  IntegrationManagementService management, IntegrationQueryService queries,
                                  IntegrationCheckService checks, IdempotentRequestService requests, IntegrationProviderRegistry providers) {
        this.identity = identity;
        this.policy = policy;
        this.management = management;
        this.queries = queries;
        this.checks = checks;
        this.requests = requests;
        this.providers = providers;
    }

    public PageResponse<IntegrationView> list(String enterprise, String cursor, Integer limit, HttpServletRequest request) {
        requireScope(enterprise, request);
        return queries.list(identity.requireUser(request.getSession(false)), enterprise, system(request), cursor, limit);
    }

    public List<IntegrationProviderView> providers(String enterprise, HttpServletRequest request) {
        requireScope(enterprise, request);
        policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.view", false);
        return providers.providers().stream().map(provider -> new IntegrationProviderView(provider.code(), provider.name(),
            provider instanceof ChannelIdentityProvider, provider instanceof ChannelMessageSender, provider.publicFields())).toList();
    }

    public IntegrationView get(String enterprise, String id, HttpServletRequest request) {
        requireScope(enterprise, request);
        return queries.get(identity.requireUser(request.getSession(false)), enterprise, system(request), id);
    }

    public ApiOperationResult create(String enterprise, IntegrationCreateRequest input, HttpServletRequest request) {
        InputValidation.request(request, IntegrationCreateRequest.class);
        return execute(enterprise, request, "integration.manage",
            actor -> ApiOperationResult.of(201, management.create(actor, enterprise, system(request), input)));
    }

    public ApiOperationResult update(String enterprise, String id, IntegrationUpdateRequest input, HttpServletRequest request) {
        InputValidation.request(request, IntegrationUpdateRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, request, "integration.manage",
            actor -> ApiOperationResult.of(200, management.update(actor, enterprise, system(request), id, revision, input)));
    }

    public ApiOperationResult rotate(String enterprise, String id, IntegrationSecretRequest input, HttpServletRequest request) {
        InputValidation.request(request, IntegrationSecretRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, request, "integration.manage",
            actor -> ApiOperationResult.of(200, management.rotate(actor, enterprise, system(request), id, revision, input.secret())));
    }

    public ApiOperationResult status(String enterprise, String id, IntegrationStatusRequest input, HttpServletRequest request) {
        InputValidation.request(request, IntegrationStatusRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, request, "integration.manage",
            actor -> ApiOperationResult.of(200, management.status(actor, enterprise, system(request), id, revision, input.status())));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, request, "integration.manage", actor -> {
            management.delete(actor, enterprise, system(request), id, revision);
            return ApiOperationResult.of(200, Map.of());
        });
    }

    public ApiOperationResult check(String enterprise, String id, HttpServletRequest request) {
        requireScope(enterprise, request);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireUser(request.getSession(false));
        Runnable authorize = () -> policy.require(identity.requireUser(request.getSession(false)), enterprise,
            system(request), "integration.test", true);
        var previous = requests.replay(request, actor, enterprise, Set.of(), authorize);
        if (previous.isPresent()) {
            return previous.get();
        }
        var prepared = checks.prepare(actor, enterprise, system(request), id, revision);
        return requests.execute(request, actor, enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(200, management.recordCheck(actor, system(request), prepared)));
    }

    private ApiOperationResult execute(String enterprise, HttpServletRequest request, String permission,
                                        Function<UserEntity, ApiOperationResult> action) {
        requireScope(enterprise, request);
        var actor = identity.requireUser(request.getSession(false));
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), permission, true),
            () -> action.apply(actor));
    }

    private boolean system(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/system/");
    }

    private void requireScope(String enterprise, HttpServletRequest request) {
        if (ChannelSessionGuard.external(request.getSession(false))) {
            if (system(request)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", "请先验证账号密码，再访问平台管理。");
            }
            identity.requireEnterprise(request.getSession(false), enterprise);
        }
    }
}
