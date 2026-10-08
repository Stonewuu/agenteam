package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Function;

/**
 * 停止与重新执行同样处理重复提交，但新的重试必须创建新的执行记录。
 */
@Service
public class RunApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final EnterpriseAuthorizationService authorization;
    private final ConversationMapper conversations;
    private final RunMapper runs;
    private final RunLifecycleService lifecycle;
    private final RunSubmissionService submission;

    public RunApiService(AuthContextService identity, IdempotentRequestService requests,
                         EnterpriseAuthorizationService authorization,
                         ConversationMapper conversations, RunMapper runs, RunLifecycleService lifecycle,
                         RunSubmissionService submission) {
        this.identity = identity;
        this.requests = requests;
        this.authorization = authorization;
        this.conversations = conversations;
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.submission = submission;
    }

    public ApiOperationResult cancel(String enterprise, String id, HttpServletRequest request) {
        return modify(enterprise, id, request, false, actor -> lifecycle.cancel(actor, id));
    }

    public ApiOperationResult retry(String enterprise, String id, HttpServletRequest request) {
        return modify(enterprise, id, request, true, actor -> submission.retry(actor, id));
    }

    private ApiOperationResult modify(String enterprise, String id, HttpServletRequest request, boolean retry,
                                      Function<AuthContext, Object> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            var run = runs.find(enterprise, id, false).filter(value -> value.userId().equals(actor.userId()))
                .orElseThrow(ResourceAuthorizationService::unavailable);
            authorization.lockAndRequire(current, retry ? "agent.run" : ExecutionAccessService.permission(run));
            conversations.find(enterprise, actor.userId(), run.conversationId(), false)
                .filter(value -> !value.status().equals("deleted"))
                .orElseThrow(ResourceAuthorizationService::unavailable);
        }, () -> ApiOperationResult.of(202, action.apply(actor)));
    }
}
