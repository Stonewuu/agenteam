package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.resource.request.ResourceStatusRequest;
import com.stonewu.agenteam.model.resource.request.RevokeVersionRequest;
import com.stonewu.agenteam.model.resource.response.ResourceImpactView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.ResourceLifecycleApiService;
import com.stonewu.agenteam.service.resource.ResourceLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/resources/{resourceId}")
public class ResourceLifecycleApiController {

    private final ResourceLifecycleApiService mutations;

    private final ResourceLifecycleService lifecycle;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ResourceLifecycleApiController(ResourceLifecycleApiService mutations, ResourceLifecycleService lifecycle,
                                          AuthContextService identity, ApiResponses responses) {
        this.mutations = mutations;
        this.lifecycle = lifecycle;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping("/impact")
    public ApiResponse<ResourceImpactView> impact(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                  HttpServletRequest request) {
        return responses.success(
            lifecycle.impact(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId), request);
    }

    @PatchMapping("/status")
    public ResponseEntity<ApiResponse<Object>> status(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @RequestBody ResourceStatusRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(mutations.status(enterpriseId, resourceId, body, request), request);
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId, HttpServletRequest request) {
        return responses.operation(mutations.delete(enterpriseId, resourceId, request), request);
    }

    @PostMapping("/restore")
    public ResponseEntity<ApiResponse<Object>> restore(@PathVariable String enterpriseId,
                                                       @PathVariable String resourceId, HttpServletRequest request) {
        return responses.operation(mutations.restore(enterpriseId, resourceId, request), request);
    }

    @PostMapping("/versions/{versionId}/revoke")
    public ResponseEntity<ApiResponse<Object>> revoke(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId, @PathVariable String versionId,
                                                      @RequestBody RevokeVersionRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(mutations.revoke(enterpriseId, resourceId, versionId, body, request), request);
    }
}
