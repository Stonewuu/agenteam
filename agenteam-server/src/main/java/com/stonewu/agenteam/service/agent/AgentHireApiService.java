package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.agent.request.HireDecisionRequest;
import com.stonewu.agenteam.model.agent.request.HireStatusRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 个人关系和审批决定与请求结果共同提交，重试不会再次建立关系。
 */
@Service
public class AgentHireApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final AgentHireService hires;

    public AgentHireApiService(AuthContextService identity, IdempotentRequestService requests, AgentHireService hires) {
        this.identity = identity;
        this.requests = requests;
        this.hires = hires;
    }

    public ApiOperationResult hire(String enterprise, HireAgentRequest value, HttpServletRequest request) {
        InputValidation.request(request, HireAgentRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> hires.authorizeHire(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(200, hires.hire(actor, value)));
    }

    public ApiOperationResult status(String enterprise, String id, HireStatusRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, HireStatusRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> hires.authorizeHire(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(200, hires.status(actor, id, value.status(), revision)));
    }

    public ApiOperationResult decide(String enterprise, String id, HireDecisionRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, HireDecisionRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> hires.authorizeDecision(identity.requireEnterprise(request.getSession(false), enterprise), id),
            () -> ApiOperationResult.of(200, hires.decide(actor, id, value, revision)));
    }

    public ApiOperationResult withdraw(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> hires.authorizeHire(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(200, hires.withdraw(actor, id, revision)));
    }
}
