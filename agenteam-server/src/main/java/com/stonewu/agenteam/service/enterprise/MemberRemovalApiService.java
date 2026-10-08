package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.model.enterprise.request.MemberRemovalRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class MemberRemovalApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final MemberRemovalPolicy policy;
    private final MemberRemovalService removal;

    public MemberRemovalApiService(AuthContextService identity, IdempotentRequestService requests,
                                   MemberRemovalPolicy policy, MemberRemovalService removal) {
        this.identity = identity;
        this.requests = requests;
        this.policy = policy;
        this.removal = removal;
    }

    public ApiOperationResult remove(String enterprise, String member, MemberRemovalRequest plan,
                                     HttpServletRequest request) {
        InputValidation.request(request, MemberRemovalRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> policy.authorize(identity.requireEnterprise(request.getSession(false), enterprise), member, true,
                true),
            () -> ApiOperationResult.of(200, removal.remove(actor, member, plan, revision)));
    }
}
