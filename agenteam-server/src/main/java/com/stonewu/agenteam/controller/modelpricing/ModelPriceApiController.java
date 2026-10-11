package com.stonewu.agenteam.controller.modelpricing;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.modelpricing.response.ModelPricePreviewView;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceDraftRequest;
import com.stonewu.agenteam.model.modelpricing.request.ModelPricePreviewRequest;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceVersionView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.modelpricing.ModelPriceApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/models/{modelId}/price-versions")
public class ModelPriceApiController {
    private final ModelPriceApiService service;
    private final ApiResponses responses;

    public ModelPriceApiController(ModelPriceApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<ModelPriceVersionView>> list(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                         @RequestParam(required = false) Long before, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, modelId, before, request), request);
    }

    @GetMapping("/{versionId}")
    public ApiResponse<ModelPriceVersionView> detail(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                      @PathVariable String versionId, HttpServletRequest request) {
        return responses.success(service.detail(enterpriseId, modelId, versionId, request), request);
    }

    @GetMapping("/current")
    public ApiResponse<ModelPriceVersionView> current(@PathVariable String enterpriseId, @PathVariable String modelId, HttpServletRequest request) {
        return responses.success(service.current(enterpriseId, modelId, request), request);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                       @RequestBody ModelPriceDraftRequest body, HttpServletRequest request) {
        return responses.operation(service.create(enterpriseId, modelId, body, request), request);
    }

    @PutMapping("/{versionId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                       @PathVariable String versionId, @RequestBody ModelPriceConfiguration body,
                                                       HttpServletRequest request) {
        return responses.operation(service.update(enterpriseId, modelId, versionId, body, request), request);
    }

    @DeleteMapping("/{versionId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                       @PathVariable String versionId, HttpServletRequest request) {
        return responses.operation(service.delete(enterpriseId, modelId, versionId, request), request);
    }

    @PostMapping("/{versionId}/publish")
    public ResponseEntity<ApiResponse<Object>> publish(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                       @PathVariable String versionId, HttpServletRequest request) {
        return responses.operation(service.publish(enterpriseId, modelId, versionId, request), request);
    }

    @PostMapping("/{versionId}/preview")
    public ApiResponse<ModelPricePreviewView> preview(@PathVariable String enterpriseId, @PathVariable String modelId,
                                                       @PathVariable String versionId, @RequestBody ModelPricePreviewRequest body,
                                                       HttpServletRequest request) {
        return responses.success(service.preview(enterpriseId, modelId, versionId, body, request), request);
    }
}
