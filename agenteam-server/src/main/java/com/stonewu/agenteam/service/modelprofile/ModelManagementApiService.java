package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import com.stonewu.agenteam.model.modelprofile.response.ManagedModelProfileView;
import com.stonewu.agenteam.model.modelprofile.response.ModelProviderView;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写入采用已有请求重复提交保护，重放结果前仍检查当前管理权限。
 */
@Service
public class ModelManagementApiService {
    private final AuthContextService identity;
    private final ModelManagementService models;
    private final IdempotentRequestService requests;
    private final RemoteModelCatalogService remoteModels;

    public ModelManagementApiService(AuthContextService identity, ModelManagementService models,
                                     IdempotentRequestService requests, RemoteModelCatalogService remoteModels) {
        this.identity = identity;
        this.models = models;
        this.requests = requests;
        this.remoteModels = remoteModels;
    }

    public List<ModelProviderView> providers(String enterprise, HttpServletRequest request) {
        return models.providers(identity.requireEnterprise(request.getSession(false), enterprise));
    }

    public List<ManagedModelProfileView> models(String enterprise, HttpServletRequest request) {
        return models.models(identity.requireEnterprise(request.getSession(false), enterprise));
    }

    public List<RemoteModelView> remoteModels(String enterprise, String providerId, HttpServletRequest request) {
        return remoteModels.list(identity.requireEnterprise(request.getSession(false), enterprise), providerId);
    }

    public ApiOperationResult createProvider(String enterprise, ModelProviderWriteRequest input,
                                             HttpServletRequest request) {
        InputValidation.request(request, ModelProviderWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true),
            () -> ApiOperationResult.of(201, models.createProvider(actor, input)));
    }

    public ApiOperationResult updateProvider(String enterprise, String id, ModelProviderWriteRequest input,
                                             HttpServletRequest request) {
        InputValidation.request(request, ModelProviderWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true),
            () -> ApiOperationResult.of(200, models.updateProvider(actor, id, revision, input)));
    }

    public ApiOperationResult deleteProvider(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true), () -> {
                models.deleteProvider(actor, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }

    public ApiOperationResult createModel(String enterprise, ModelProfileWriteRequest input,
                                          HttpServletRequest request) {
        InputValidation.request(request, ModelProfileWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true),
            () -> ApiOperationResult.of(201, models.createModel(actor, input)));
    }

    public ApiOperationResult updateModel(String enterprise, String id, ModelProfileWriteRequest input,
                                          HttpServletRequest request) {
        InputValidation.request(request, ModelProfileWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true),
            () -> ApiOperationResult.of(200, models.updateModel(actor, id, revision, input)));
    }

    public ApiOperationResult deleteModel(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> models.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true), () -> {
                models.deleteModel(actor, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }
}
