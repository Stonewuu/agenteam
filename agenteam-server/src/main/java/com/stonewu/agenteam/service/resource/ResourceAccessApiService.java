package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceViewMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.request.ResourceGrantsRequest;
import com.stonewu.agenteam.model.resource.request.TransferResourceRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 资源授权与所有权转交按当前管理资格执行，普通编辑授权不会扩大为授权管理。
 */
@Service
public class ResourceAccessApiService {
    private final AuthContextService identity;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceAuthorizationService access;
    private final ResourceGrantService grants;
    private final ResourceOwnershipService owners;
    private final ResourceMapper resources;
    private final ResourceViewMapper views;
    private final IdempotentRequestService requests;

    public ResourceAccessApiService(AuthContextService identity, EnterpriseAuthorizationService authorization,
                                    ResourceAuthorizationService access,
                                    ResourceGrantService grants, ResourceOwnershipService owners,
                                    ResourceMapper resources, ResourceViewMapper views,
                                    IdempotentRequestService requests) {
        this.identity = identity;
        this.authorization = authorization;
        this.access = access;
        this.grants = grants;
        this.owners = owners;
        this.resources = resources;
        this.views = views;
        this.requests = requests;
    }

    public List<ResourceGrantSpec> grants(String enterprise, String id, HttpServletRequest request) {
        return grants.list(identity.requireEnterprise(request.getSession(false), enterprise), id);
    }

    public ApiOperationResult grants(String enterprise, String id, ResourceGrantsRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, ResourceGrantsRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            authorization.lockAndRequire(current, "resource.grants.manage");
            access.requireGrantManager(current, id, true);
        }, () -> {
            grants.replace(actor, id, revision, value.grants());
            return ApiOperationResult.of(200,
                views.summary(actor, resources.find(enterprise, id, false, false).orElseThrow()));
        });
    }

    public ApiOperationResult transfer(String enterprise, String id, TransferResourceRequest value,
                                       HttpServletRequest request) {
        InputValidation.request(request, TransferResourceRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> owners.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id),
            () -> ApiOperationResult.of(200,
                views.summary(actor, owners.transfer(actor, id, value.ownerUserId(), revision))));
    }
}
