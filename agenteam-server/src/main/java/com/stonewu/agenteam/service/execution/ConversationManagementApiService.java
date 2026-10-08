package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.request.ConversationUpdateInput;
import com.stonewu.agenteam.model.execution.request.MessageFeedbackInput;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 管理请求先检查最新归属与权限，再读取重复结果或执行一次事务。
 */
@Service
public class ConversationManagementApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final ConversationManagementService management;

    public ConversationManagementApiService(AuthContextService identity, IdempotentRequestService requests,
                                            ConversationManagementService management) {
        this.identity = identity;
        this.requests = requests;
        this.management = management;
    }

    public ApiOperationResult update(String enterprise, String id, ConversationUpdateInput input,
                                     HttpServletRequest request) {
        InputValidation.request(request, ConversationUpdateInput.class);
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, id, request, actor -> management.update(actor, id, input, revision));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, id, request, actor -> {
            management.delete(actor, id, revision);
            return Map.of("success", true);
        });
    }

    public ApiOperationResult restore(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return modify(enterprise, id, request, actor -> management.restore(actor, id, revision));
    }

    public ApiOperationResult feedback(String enterprise, String id, MessageFeedbackInput input,
                                       HttpServletRequest request) {
        InputValidation.request(request, MessageFeedbackInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> management.authorizeFeedback(identity.requireEnterprise(request.getSession(false), enterprise), id),
            () -> {
                management.feedback(actor, id, input);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }

    private ApiOperationResult modify(String enterprise, String id, HttpServletRequest request,
                                      Function<AuthContext, Object> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> management.authorize(identity.requireEnterprise(request.getSession(false), enterprise), id),
            () -> ApiOperationResult.of(200, action.apply(actor)));
    }
}
