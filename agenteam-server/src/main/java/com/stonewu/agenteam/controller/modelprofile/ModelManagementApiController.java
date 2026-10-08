package com.stonewu.agenteam.controller.modelprofile;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import com.stonewu.agenteam.model.modelprofile.response.ManagedModelProfileView;
import com.stonewu.agenteam.model.modelprofile.response.ModelProviderView;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.modelprofile.ModelManagementApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ModelManagementApiController {

    private final ModelManagementApiService models;

    private final ApiResponses responses;

    public ModelManagementApiController(ModelManagementApiService models, ApiResponses responses) {
        this.models = models;
        this.responses = responses;
    }

    @GetMapping("/model-providers")
    public ApiResponse<List<ModelProviderView>> providers(@PathVariable String enterpriseId,
                                                          HttpServletRequest request) {
        return responses.success(models.providers(enterpriseId, request), request);
    }

    @PostMapping("/model-providers")
    public ResponseEntity<ApiResponse<Object>> createProvider(@PathVariable String enterpriseId,
                                                              @RequestBody ModelProviderWriteRequest body,
                                                              HttpServletRequest request) {
        return responses.operation(models.createProvider(enterpriseId, body, request), request);
    }

    @GetMapping("/model-providers/{id}/remote-models")
    public ApiResponse<List<RemoteModelView>> remoteModels(@PathVariable String enterpriseId, @PathVariable String id,
                                                           HttpServletRequest request) {
        return responses.success(models.remoteModels(enterpriseId, id, request), request);
    }

    @PutMapping("/model-providers/{id}")
    public ResponseEntity<ApiResponse<Object>> updateProvider(@PathVariable String enterpriseId,
                                                              @PathVariable String id,
                                                              @RequestBody ModelProviderWriteRequest body,
                                                              HttpServletRequest request) {
        return responses.operation(models.updateProvider(enterpriseId, id, body, request), request);
    }

    @DeleteMapping("/model-providers/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteProvider(@PathVariable String enterpriseId,
                                                              @PathVariable String id, HttpServletRequest request) {
        return responses.operation(models.deleteProvider(enterpriseId, id, request), request);
    }

    @GetMapping("/models")
    public ApiResponse<List<ManagedModelProfileView>> models(@PathVariable String enterpriseId,
                                                             HttpServletRequest request) {
        return responses.success(models.models(enterpriseId, request), request);
    }

    @PostMapping("/models")
    public ResponseEntity<ApiResponse<Object>> createModel(@PathVariable String enterpriseId,
                                                           @RequestBody ModelProfileWriteRequest body,
                                                           HttpServletRequest request) {
        return responses.operation(models.createModel(enterpriseId, body, request), request);
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<ApiResponse<Object>> updateModel(@PathVariable String enterpriseId, @PathVariable String id,
                                                           @RequestBody ModelProfileWriteRequest body,
                                                           HttpServletRequest request) {
        return responses.operation(models.updateModel(enterpriseId, id, body, request), request);
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteModel(@PathVariable String enterpriseId, @PathVariable String id,
                                                           HttpServletRequest request) {
        return responses.operation(models.deleteModel(enterpriseId, id, request), request);
    }
}
