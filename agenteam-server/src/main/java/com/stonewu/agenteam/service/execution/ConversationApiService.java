package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 请求去重和执行提交共同提交，重复结果返回前重新校验当前身份与对象范围。
 */
@Service
public class ConversationApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final RunSubmissionService submission;
    private final EnterpriseAuthorizationService authorization;
    private final ConversationMapper conversations;
    private final ConversationModelService models;

    public ConversationApiService(AuthContextService identity, IdempotentRequestService requests,
                                  RunSubmissionService submission, EnterpriseAuthorizationService authorization,
                                  ConversationMapper conversations, ConversationModelService models) {
        this.identity = identity;
        this.requests = requests;
        this.submission = submission;
        this.authorization = authorization;
        this.conversations = conversations;
        this.models = models;
    }

    public ApiOperationResult create(String enterprise, NewConversationInput value, HttpServletRequest request) {
        InputValidation.request(request, NewConversationInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            authorization.lockAndRequire(current, "agent.run");
        }, () -> ApiOperationResult.of(202, submission.create(actor, value)));
    }

    public ApiOperationResult send(String enterprise, String id, MessageInput value, HttpServletRequest request) {
        InputValidation.request(request, MessageInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            authorization.lockAndRequire(current, "agent.run");
            conversations.find(enterprise, actor.userId(), id, false).filter(row -> !row.status().equals("deleted"))
                .orElseThrow(ResourceAuthorizationService::unavailable);
        }, () -> ApiOperationResult.of(202, submission.send(actor, id, value)));
    }

    public ApiOperationResult selectModel(String enterprise, String id, ModelSelection value,
                                          HttpServletRequest request) {
        InputValidation.request(request, ModelSelection.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            models.authorize(current, id, true);
        }, () -> ApiOperationResult.of(200, models.select(actor, id, value, revision)));
    }
}
