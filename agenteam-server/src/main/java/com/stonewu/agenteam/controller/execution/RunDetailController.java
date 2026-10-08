package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.RunQueryService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/runs/{runId}")
public class RunDetailController {

    private final AuthContextService identity;

    private final RunQueryService queries;

    private final ApiResponses responses;

    public RunDetailController(AuthContextService identity, RunQueryService queries, ApiResponses responses) {
        this.identity = identity;
        this.queries = queries;
        this.responses = responses;
    }

    @GetMapping("/attempts")
    public ApiResponse<?> attempts(@PathVariable String enterpriseId, @PathVariable String runId,
                                   HttpServletRequest request) {
        return responses.success(
            queries.attempts(identity.requireEnterprise(request.getSession(false), enterpriseId), runId), request);
    }

    @GetMapping("/steps")
    public ApiResponse<?> steps(@PathVariable String enterpriseId, @PathVariable String runId,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(
            queries.steps(identity.requireEnterprise(request.getSession(false), enterpriseId), runId, cursor, limit),
            request);
    }
}
