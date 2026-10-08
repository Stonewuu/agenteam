package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.execution.request.ApprovalDecisionRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.RunApprovalApiService;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 确认接口不接受新的工具参数，也不授予管理员代替原成员批准的能力。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class RunApprovalController {

    private final RunApprovalService approvals;

    private final RunApprovalApiService mutations;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public RunApprovalController(RunApprovalService approvals, RunApprovalApiService mutations,
                                 AuthContextService identity, ApiResponses responses) {
        this.approvals = approvals;
        this.mutations = mutations;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping("/runs/{runId}/approvals")
    public Object list(@PathVariable String enterpriseId, @PathVariable String runId, HttpServletRequest request) {
        return responses.success(
            approvals.list(identity.requireEnterprise(request.getSession(false), enterpriseId), runId), request);
    }

    @PostMapping("/approvals/{approvalId}/decision")
    public Object decide(@PathVariable String enterpriseId, @PathVariable String approvalId,
                         @RequestBody ApprovalDecisionRequest input, HttpServletRequest request) {
        return responses.operation(mutations.decide(enterpriseId, approvalId, input, request), request);
    }
}
