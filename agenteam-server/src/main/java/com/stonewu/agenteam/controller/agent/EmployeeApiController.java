package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.model.agent.response.EmployeeView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/employees")
public class EmployeeApiController {

    private final EmployeeQueryService employees;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public EmployeeApiController(EmployeeQueryService employees, AuthContextService identity, ApiResponses responses) {
        this.employees = employees;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<EmployeeView>> list(@PathVariable String enterpriseId,
                                                        @RequestParam(required = false) String tab,
                                                        @RequestParam(required = false) String query,
                                                        @RequestParam(required = false) List<String> tagIds,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit,
                                                        HttpServletRequest request) {
        return responses.success(
            employees.list(identity.requireEnterprise(request.getSession(false), enterpriseId), tab, query, tagIds,
                cursor, limit), request);
    }

    @GetMapping("/{agentId}")
    public ApiResponse<EmployeeView> detail(@PathVariable String enterpriseId, @PathVariable String agentId,
                                            HttpServletRequest request) {
        return responses.success(
            employees.detail(identity.requireEnterprise(request.getSession(false), enterpriseId), agentId), request);
    }
}
