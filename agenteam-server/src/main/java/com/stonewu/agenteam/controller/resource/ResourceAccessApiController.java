package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.request.ResourceGrantsRequest;
import com.stonewu.agenteam.model.resource.request.TransferResourceRequest;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.ResourceAccessApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/resources/{resourceId}")
public class ResourceAccessApiController {

    private final ResourceAccessApiService access;

    private final ApiResponses responses;

    public ResourceAccessApiController(ResourceAccessApiService access, ApiResponses responses) {
        this.access = access;
        this.responses = responses;
    }

    @GetMapping("/grants")
    public ApiResponse<List<ResourceGrantSpec>> grants(@PathVariable String enterpriseId,
                                                       @PathVariable String resourceId, HttpServletRequest request) {
        return responses.success(access.grants(enterpriseId, resourceId, request), request);
    }

    @PutMapping("/grants")
    public ResponseEntity<ApiResponse<Object>> grants(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @RequestBody ResourceGrantsRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(access.grants(enterpriseId, resourceId, body, request), request);
    }

    @PutMapping("/owner")
    public ResponseEntity<ApiResponse<Object>> owner(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                     @RequestBody TransferResourceRequest body,
                                                     HttpServletRequest request) {
        return responses.operation(access.transfer(enterpriseId, resourceId, body, request), request);
    }
}
