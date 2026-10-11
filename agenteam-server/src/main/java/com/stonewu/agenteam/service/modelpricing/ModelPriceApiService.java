package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.modelpricing.response.ModelPricePreviewView;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceDraftRequest;
import com.stonewu.agenteam.model.modelpricing.request.ModelPricePreviewRequest;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceVersionView;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceCatalogView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.stonewu.agenteam.model.auth.entity.AuthContext;

@Service
public class ModelPriceApiService {
    private final AuthContextService identity;
    private final ModelPriceService prices;
    private final ModelPricePreviewService previews;
    private final IdempotentRequestService requests;
    private final ModelPriceCatalogService catalog;

    public ModelPriceApiService(AuthContextService identity, ModelPriceService prices, ModelPricePreviewService previews,
                                 IdempotentRequestService requests, ModelPriceCatalogService catalog) {
        this.identity = identity;
        this.prices = prices;
        this.previews = previews;
        this.requests = requests;
        this.catalog = catalog;
    }

    public ModelPriceCatalogView catalog(String enterprise, String model, String query, String provider, int offset, boolean suggested,
                                        HttpServletRequest request) {
        return catalog.search(identity.requireEnterprise(request.getSession(false), enterprise), model, query, provider, offset, suggested);
    }

    public List<ModelPriceVersionView> list(String enterprise, String model, Long before, HttpServletRequest request) {
        return prices.list(identity.requireEnterprise(request.getSession(false), enterprise), model, before);
    }

    public ModelPriceVersionView detail(String enterprise, String model, String version, HttpServletRequest request) {
        return prices.detail(identity.requireEnterprise(request.getSession(false), enterprise), model, version);
    }

    public ModelPriceVersionView current(String enterprise, String model, HttpServletRequest request) {
        return prices.current(identity.requireEnterprise(request.getSession(false), enterprise), model);
    }

    public ModelPricePreviewView preview(String enterprise, String model, String version, ModelPricePreviewRequest body, HttpServletRequest request) {
        InputValidation.request(request, ModelPricePreviewRequest.class);
        return previews.preview(identity.requireEnterprise(request.getSession(false), enterprise), model, version, body);
    }

    public ApiOperationResult create(String enterprise, String model, ModelPriceDraftRequest body, HttpServletRequest request) {
        InputValidation.request(request, ModelPriceDraftRequest.class);
        if (body == null || (body.copyFromVersionId() == null) == (body.configuration() == null)) {
            throw ApiException.invalidField("configuration", "请填写新价格，或选择要复制的历史版本。");
        }
        return mutate(enterprise, request, actor -> ApiOperationResult.of(201, body.copyFromVersionId() == null
            ? prices.create(actor, model, body.configuration()) : prices.copy(actor, model, body.copyFromVersionId())));
    }

    public ApiOperationResult update(String enterprise, String model, String version, ModelPriceConfiguration body, HttpServletRequest request) {
        InputValidation.request(request, ModelPriceConfiguration.class);
        long revision = RequestPreconditions.revision(request);
        return mutate(enterprise, request, actor -> ApiOperationResult.of(200, prices.update(actor, model, version, revision, body)));
    }

    public ApiOperationResult publish(String enterprise, String model, String version, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return mutate(enterprise, request, actor -> ApiOperationResult.of(200, prices.publish(actor, model, version, revision)));
    }

    public ApiOperationResult delete(String enterprise, String model, String version, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return mutate(enterprise, request, actor -> {
            prices.delete(actor, model, version, revision);
            return ApiOperationResult.of(200, Map.of("success", true));
        });
    }

    private ApiOperationResult mutate(String enterprise, HttpServletRequest request, Function<AuthContext, ApiOperationResult> operation) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> prices.authorize(identity.requireEnterprise(request.getSession(false), enterprise), true), () -> operation.apply(actor));
    }
}
