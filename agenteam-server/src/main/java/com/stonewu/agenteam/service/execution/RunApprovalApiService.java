package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.request.ApprovalDecisionRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 重复确认复用原决定，不能产生第二次派发。
 */
@Service
public class RunApprovalApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final RunApprovalService approvals;

    public RunApprovalApiService(AuthContextService identity, IdempotentRequestService requests,
                                 RunApprovalService approvals) {
        this.identity = identity;
        this.requests = requests;
        this.approvals = approvals;
    }

    public ApiOperationResult decide(String enterprise, String id, ApprovalDecisionRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, ApprovalDecisionRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> approvals.authorizeDecision(identity.requireEnterprise(request.getSession(false), enterprise), id),
            () -> ApiOperationResult.of(200, approvals.decide(actor, id, revision, input)));
    }
}
