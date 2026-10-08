package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.agent.request.HireDecisionRequest;
import com.stonewu.agenteam.model.agent.request.HireStatusRequest;
import com.stonewu.agenteam.model.agent.response.AgentHireApplicationView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.agent.AgentHireApiService;
import com.stonewu.agenteam.service.agent.AgentHireQueryService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class AgentHireApiController {

    private final AgentHireApiService hires;

    private final ApiResponses responses;

    private final AgentHireQueryService queries;

    private final AuthContextService identity;

    public AgentHireApiController(AgentHireApiService hires, ApiResponses responses, AgentHireQueryService queries,
                                  AuthContextService identity) {
        this.hires = hires;
        this.responses = responses;
        this.queries = queries;
        this.identity = identity;
    }

    @GetMapping("/hire-requests")
    public ApiResponse<PageResponse<AgentHireApplicationView>> applications(@PathVariable String enterpriseId,
                                                                            @RequestParam(required = false) String cursor,
                                                                            @RequestParam(required = false) Integer limit,
                                                                            HttpServletRequest request) {
        return responses.success(
            queries.list(identity.requireEnterprise(request.getSession(false), enterpriseId), cursor, limit), request);
    }

    @PostMapping("/hires")
    public ResponseEntity<ApiResponse<Object>> hire(@PathVariable String enterpriseId,
                                                    @RequestBody HireAgentRequest body, HttpServletRequest request) {
        return responses.operation(hires.hire(enterpriseId, body, request), request);
    }

    @GetMapping("/hire-requests/{applicationId}")
    public ApiResponse<AgentHireApplicationView> application(@PathVariable String enterpriseId,
                                                             @PathVariable String applicationId,
                                                             HttpServletRequest request) {
        return responses.success(
            queries.get(identity.requireEnterprise(request.getSession(false), enterpriseId), applicationId), request);
    }

    @PatchMapping("/hires/{hireId}")
    public ResponseEntity<ApiResponse<Object>> status(@PathVariable String enterpriseId, @PathVariable String hireId,
                                                      @RequestBody HireStatusRequest body, HttpServletRequest request) {
        return responses.operation(hires.status(enterpriseId, hireId, body, request), request);
    }

    @PostMapping("/hire-requests/{applicationId}/decision")
    public ResponseEntity<ApiResponse<Object>> decide(@PathVariable String enterpriseId,
                                                      @PathVariable String applicationId,
                                                      @RequestBody HireDecisionRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(hires.decide(enterpriseId, applicationId, body, request), request);
    }

    @PostMapping("/hire-requests/{applicationId}/withdraw")
    public ResponseEntity<ApiResponse<Object>> withdraw(@PathVariable String enterpriseId,
                                                        @PathVariable String applicationId,
                                                        HttpServletRequest request) {
        return responses.operation(hires.withdraw(enterpriseId, applicationId, request), request);
    }
}
