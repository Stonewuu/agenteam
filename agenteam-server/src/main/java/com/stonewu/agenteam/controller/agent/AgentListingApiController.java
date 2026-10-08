package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.resource.request.AgentListingRequest;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.ResourceMutationApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/agents/{resourceId}/listing")
public class AgentListingApiController {

    private final ResourceMutationApiService mutations;

    private final ApiResponses responses;

    public AgentListingApiController(ResourceMutationApiService mutations, ApiResponses responses) {
        this.mutations = mutations;
        this.responses = responses;
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @RequestBody AgentListingRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(mutations.listing(enterpriseId, resourceId, body, request), request);
    }
}
