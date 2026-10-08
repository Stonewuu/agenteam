package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.resource.request.ResourceStatusRequest;
import com.stonewu.agenteam.model.resource.request.RevokeVersionRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class ResourceLifecycleApiService {
    private final AuthContextService identity;
    private final ResourceLifecycleService lifecycle;
    private final IdempotentRequestService requests;

    public ResourceLifecycleApiService(AuthContextService identity, ResourceLifecycleService lifecycle,
                                       IdempotentRequestService requests) {
        this.identity = identity;
        this.lifecycle = lifecycle;
        this.requests = requests;
    }

    public ApiOperationResult status(String enterprise, String id, ResourceStatusRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, ResourceStatusRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> lifecycle.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, "edit",
                false),
            () -> ApiOperationResult.of(200, lifecycle.status(actor, id, value.status(), revision)));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> lifecycle.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, "delete",
                true), () -> {
                lifecycle.delete(actor, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }

    public ApiOperationResult restore(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> lifecycle.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, "delete",
                true),
            () -> ApiOperationResult.of(200, lifecycle.restore(actor, id, revision)));
    }

    public ApiOperationResult revoke(String enterprise, String id, String versionId, RevokeVersionRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, RevokeVersionRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> lifecycle.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id, "publish",
                false),
            () -> ApiOperationResult.of(200, lifecycle.revoke(actor, id, versionId, value.reason(), revision)));
    }
}
