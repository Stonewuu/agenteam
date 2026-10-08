package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.request.ActiveStatusPayload;
import com.stonewu.agenteam.model.enterprise.request.EnterpriseUpdatePayload;
import com.stonewu.agenteam.model.enterprise.request.MemberUpdatePayload;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.enterprise.OrganizationApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises")
public class EnterpriseDefinitionApiController {

    private final OrganizationApiService organization;

    private final ApiResponses responses;

    public EnterpriseDefinitionApiController(OrganizationApiService organization, ApiResponses responses) {
        this.organization = organization;
        this.responses = responses;
    }

    @PatchMapping("/{enterpriseId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId,
                                                      @RequestBody EnterpriseUpdatePayload value,
                                                      HttpServletRequest request) {
        return responses.operation(organization.enterprise(enterpriseId, value, request), request);
    }

    @PatchMapping("/{enterpriseId}/members/{userId}")
    public ResponseEntity<ApiResponse<Object>> member(@PathVariable String enterpriseId, @PathVariable String userId,
                                                      @RequestBody MemberUpdatePayload value,
                                                      HttpServletRequest request) {
        return responses.operation(organization.member(enterpriseId, userId, value, request), request);
    }

    @PatchMapping("/{enterpriseId}/members/{userId}/status")
    public ResponseEntity<ApiResponse<Object>> memberStatus(@PathVariable String enterpriseId,
                                                            @PathVariable String userId,
                                                            @RequestBody ActiveStatusPayload value,
                                                            HttpServletRequest request) {
        return responses.operation(organization.memberStatus(enterpriseId, userId, value.status(), request), request);
    }
}
