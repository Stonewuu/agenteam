package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.ExecutionResultViewedService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/runs/{runId}/result-viewed")
public class ExecutionResultViewedController {

    private final AuthContextService identity;

    private final ExecutionResultViewedService results;

    public ExecutionResultViewedController(AuthContextService identity, ExecutionResultViewedService results) {
        this.identity = identity;
        this.results = results;
    }

    @PostMapping
    public ResponseEntity<Void> viewed(@PathVariable String enterpriseId, @PathVariable String runId,
                                       HttpServletRequest request) {
        results.viewed(identity.requireEnterprise(request.getSession(false), enterpriseId), runId);
        return ResponseEntity.noContent().build();
    }
}
