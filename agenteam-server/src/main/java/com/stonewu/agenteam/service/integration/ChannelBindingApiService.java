package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.integration.request.ChannelBindingUpdateRequest;
import com.stonewu.agenteam.model.integration.request.ChannelBindingConfirmRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/** 用户绑定写请求沿用并发版本与请求重复保护。 */
@Service
public class ChannelBindingApiService {
    private final ChannelBindingService bindings;
    private final ChannelAuthorizationService authorization;
    private final AuthContextService identity;
    private final LocalAuthenticationService local;
    private final IdempotentRequestService requests;
    private final EnterpriseMapper enterprises;

    public ChannelBindingApiService(ChannelBindingService bindings, ChannelAuthorizationService authorization,
                                    AuthContextService identity, LocalAuthenticationService local, IdempotentRequestService requests,
                                    EnterpriseMapper enterprises) {
        this.bindings = bindings;
        this.authorization = authorization;
        this.identity = identity;
        this.local = local;
        this.requests = requests;
        this.enterprises = enterprises;
    }

    public ApiOperationResult confirm(String enterprise, ChannelBindingConfirmRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelBindingConfirmRequest.class);
        var actor = local.requireRecent(request.getSession(false));
        return requests.execute(request, actor, enterprise, Set.of(), () -> authorize(enterprise, request),
            () -> ApiOperationResult.of(201, authorization.confirm(enterprise, input, request.getSession(false))));
    }

    public ApiOperationResult update(String enterprise, String id, ChannelBindingUpdateRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelBindingUpdateRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = local.requireRecent(request.getSession(false));
        return requests.execute(request, actor, enterprise, Set.of(), () -> authorize(enterprise, request),
            () -> ApiOperationResult.of(200, bindings.update(enterprise, id, revision, input, request.getSession(false))));
    }

    public ApiOperationResult revoke(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = local.requireRecent(request.getSession(false));
        return requests.execute(request, actor, enterprise, Set.of(), () -> authorize(enterprise, request), () -> {
            bindings.revoke(enterprise, id, revision, request.getSession(false));
            return ApiOperationResult.of(200, Map.of());
        });
    }

    private void authorize(String enterprise, HttpServletRequest request) {
        enterprises.lockEnterprise(enterprise);
        local.requireRecent(request.getSession(false));
        identity.requireEnterprise(request.getSession(false), enterprise);
    }
}
