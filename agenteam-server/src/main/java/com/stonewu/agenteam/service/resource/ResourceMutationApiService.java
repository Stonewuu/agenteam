package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.ResourceViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.resource.request.*;
import com.stonewu.agenteam.service.agent.AgentListingService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 修改请求与业务结果同事务保存；重复返回前仍检查当前身份和对应对象权限。
 */
@Service
public class ResourceMutationApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final ResourcePolicy policy;
    private final ResourceDraftService drafts;
    private final ResourcePublishService publications;
    private final AgentListingService listings;
    private final ResourceViewMapper views;

    public ResourceMutationApiService(AuthContextService identity, IdempotentRequestService requests,
                                      ResourcePolicy policy, ResourceDraftService drafts,
                                      ResourcePublishService publications,
                                      AgentListingService listings, ResourceViewMapper views) {
        this.identity = identity;
        this.requests = requests;
        this.policy = policy;
        this.drafts = drafts;
        this.publications = publications;
        this.listings = listings;
        this.views = views;
    }

    public ApiOperationResult create(String enterprise, ResourceCreateRequest value, HttpServletRequest request) {
        InputValidation.request(request, ResourceCreateRequest.class);
        var kind = ResourceInput.kind(value.kind());
        return modify(enterprise, request, Set.of("/tagIds"), actor -> policy.authorizeCreation(actor, kind),
            actor -> ApiOperationResult.of(201, drafts.create(actor, value)));
    }

    public ApiOperationResult draft(String enterprise, String id, DraftWriteRequest value, HttpServletRequest request) {
        InputValidation.request(request, DraftWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, request, Set.of("/tagIds"), actor -> policy.authorize(actor, id, "edit", true, false),
            actor -> ApiOperationResult.of(200, drafts.save(actor, id, revision, value)));
    }

    public ApiOperationResult publish(String enterprise, String id, ResourcePublishRequest value,
                                      HttpServletRequest request) {
        InputValidation.request(request, ResourcePublishRequest.class);
        InputValidation.nonNullWhenPresent(request, "grants", "listing");
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, request, Set.of(), actor -> policy.authorize(actor, id, "publish", true, false),
            actor -> ApiOperationResult.of(201, publications.publish(actor, id, value, revision)));
    }

    public ApiOperationResult listing(String enterprise, String id, AgentListingRequest value,
                                      HttpServletRequest request) {
        InputValidation.request(request, AgentListingRequest.class);
        InputValidation.nonNullWhenPresent(request, "useGrants");
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, request, Set.of(), actor -> policy.authorize(actor, id, "publish", true, false),
            actor -> ApiOperationResult.of(200, views.summary(actor, listings.update(actor, id, value, revision))));
    }

    public ApiOperationResult copy(String enterprise, String id, CopyResourceRequest value,
                                   HttpServletRequest request) {
        InputValidation.request(request, CopyResourceRequest.class);
        return modify(enterprise, request, Set.of(), actor -> {
            var original = policy.authorize(actor, id, "view", true, false);
            policy.authorizeCreation(actor, original.kind());
        }, actor -> ApiOperationResult.of(201, drafts.copy(actor, id, value.name())));
    }

    public ApiOperationResult loadVersion(String enterprise, String id, String versionId, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, request, Set.of(), actor -> policy.authorize(actor, id, "edit", true, false),
            actor -> ApiOperationResult.of(200, drafts.loadVersion(actor, id, versionId, revision)));
    }

    private ApiOperationResult modify(String enterprise, HttpServletRequest request, Set<String> unordered,
                                      Consumer<AuthContext> authorize,
                                      Function<AuthContext, ApiOperationResult> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, unordered,
            () -> authorize.accept(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> action.apply(actor));
    }
}
