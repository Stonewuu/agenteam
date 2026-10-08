package com.stonewu.agenteam.service.security;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.security.request.CredentialRotateRequest;
import com.stonewu.agenteam.model.security.request.CredentialWriteRequest;
import com.stonewu.agenteam.model.security.response.CredentialSummaryView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 重复修改返回原结果，但每次仍验证当前身份和凭据管理资格。
 */
@Service
public class CredentialApiService {
    private final AuthContextService identity;
    private final CredentialService credentials;
    private final IdempotentRequestService requests;

    public CredentialApiService(AuthContextService identity, CredentialService credentials,
                                IdempotentRequestService requests) {
        this.identity = identity;
        this.credentials = credentials;
        this.requests = requests;
    }

    public PageResponse<CredentialSummaryView> list(String enterprise, String cursor, Integer limit,
                                                    HttpServletRequest request) {
        return credentials.list(identity.requireEnterprise(request.getSession(false), enterprise), cursor, limit);
    }

    public ApiOperationResult create(String enterprise, CredentialWriteRequest input, HttpServletRequest request) {
        InputValidation.request(request, CredentialWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> credentials.authorize(identity.requireEnterprise(request.getSession(false), enterprise), null, true),
            () -> ApiOperationResult.of(201, credentials.create(actor, input)));
    }

    public ApiOperationResult rotate(String enterprise, String id, String secret, HttpServletRequest request) {
        InputValidation.request(request, CredentialRotateRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> credentials.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, true),
            () -> ApiOperationResult.of(200, credentials.rotate(actor, id, revision, secret)));
    }

    public ApiOperationResult revoke(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> credentials.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, true),
            () -> {
                credentials.revoke(actor, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }
}
