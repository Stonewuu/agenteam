package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.service.execution.RunApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户操作始终绑定当前企业与本人执行。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/runs/{runId}")
public class RunApiController {

    private final RunApiService service;

    private final ApiResponses responses;

    public RunApiController(RunApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(@PathVariable String enterpriseId, @PathVariable String runId,
                                    HttpServletRequest request) {
        return responses.operation(service.cancel(enterpriseId, runId, request), request);
    }

    @PostMapping("/retry")
    public ResponseEntity<?> retry(@PathVariable String enterpriseId, @PathVariable String runId,
                                   HttpServletRequest request) {
        return responses.operation(service.retry(enterpriseId, runId, request), request);
    }
}
