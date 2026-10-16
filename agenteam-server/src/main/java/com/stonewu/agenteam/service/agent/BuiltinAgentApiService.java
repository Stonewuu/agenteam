package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.agent.request.BuiltinAgentStartInput;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 内置助手首次创建与发送共用请求去重，重试不会重复安装或提交任务。 */
@Service
public class BuiltinAgentApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final EnterpriseAuthorizationService authorization;
    private final BuiltinAgentService agents;

    public BuiltinAgentApiService(AuthContextService identity, IdempotentRequestService requests,
                                   EnterpriseAuthorizationService authorization, BuiltinAgentService agents) {
        this.identity = identity;
        this.requests = requests;
        this.authorization = authorization;
        this.agents = agents;
    }

    public ApiOperationResult start(String enterprise, String code, BuiltinAgentStartInput input, HttpServletRequest request) {
        InputValidation.request(request, BuiltinAgentStartInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            authorization.lockAndRequire(current, "agent.run");
            agents.require(code).prepareInput(current, input.targetResourceId(), input.message());
        }, () -> ApiOperationResult.of(202, agents.start(actor, code, input)));
    }
}
