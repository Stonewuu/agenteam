package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.workflow.response.WorkflowDependencyOptions;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.workflow.WorkflowDependencyOptionsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点候选的协议入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/workflows/dependency-options")
public class WorkflowDependencyOptionsController {

    private final AuthContextService identity;

    private final WorkflowDependencyOptionsService options;

    private final ApiResponses responses;

    public WorkflowDependencyOptionsController(AuthContextService identity, WorkflowDependencyOptionsService options,
                                               ApiResponses responses) {
        this.identity = identity;
        this.options = options;
        this.responses = responses;
    }

    @GetMapping("/{versionId}")
    public ApiResponse<WorkflowDependencyOptions> get(@PathVariable String enterpriseId, @PathVariable String versionId,
                                                      HttpServletRequest request) {
        return responses.success(
            options.options(identity.requireEnterprise(request.getSession(false), enterpriseId), versionId), request);
    }
}
