package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.request.ActiveStatusPayload;
import com.stonewu.agenteam.model.enterprise.request.TeamMembersPayload;
import com.stonewu.agenteam.model.enterprise.request.TeamWritePayload;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.enterprise.OrganizationApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/teams")
public class TeamDefinitionApiController {

    private final OrganizationApiService organization;

    private final ApiResponses responses;

    public TeamDefinitionApiController(OrganizationApiService organization, ApiResponses responses) {
        this.organization = organization;
        this.responses = responses;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody TeamWritePayload value, HttpServletRequest request) {
        return responses.operation(organization.createTeam(enterpriseId, value, request), request);
    }

    @PutMapping("/{teamId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @PathVariable String teamId,
                                                      @RequestBody TeamWritePayload value, HttpServletRequest request) {
        return responses.operation(organization.team(enterpriseId, teamId, value, request), request);
    }

    @PutMapping("/{teamId}/members")
    public ResponseEntity<ApiResponse<Object>> members(@PathVariable String enterpriseId, @PathVariable String teamId,
                                                       @RequestBody TeamMembersPayload value,
                                                       HttpServletRequest request) {
        return responses.operation(organization.teamMembers(enterpriseId, teamId, value.memberIds(), request), request);
    }

    @PatchMapping("/{teamId}/status")
    public ResponseEntity<ApiResponse<Object>> status(@PathVariable String enterpriseId, @PathVariable String teamId,
                                                      @RequestBody ActiveStatusPayload value,
                                                      HttpServletRequest request) {
        return responses.operation(organization.teamStatus(enterpriseId, teamId, value.status(), request), request);
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId, @PathVariable String teamId,
                                                      HttpServletRequest request) {
        return responses.operation(organization.deleteTeam(enterpriseId, teamId, request), request);
    }
}
