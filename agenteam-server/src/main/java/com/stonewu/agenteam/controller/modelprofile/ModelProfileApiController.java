package com.stonewu.agenteam.controller.modelprofile;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.modelprofile.response.ModelProfileView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/model-profiles")
public class ModelProfileApiController {

    private final AuthContextService identity;

    private final ModelProfileCatalog models;

    private final ApiResponses responses;

    public ModelProfileApiController(AuthContextService identity, ModelProfileCatalog models, ApiResponses responses) {
        this.identity = identity;
        this.models = models;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<ModelProfileView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(models.list(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }
}
