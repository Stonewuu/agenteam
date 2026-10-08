package com.stonewu.agenteam.service.project;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.project.request.CreateProjectInput;
import com.stonewu.agenteam.model.project.request.SelectProjectInput;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 项目修改沿用现有请求去重和会话版本检查。
 */
@Service
public class ProjectApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final EnterpriseAuthorizationService authorization;
    private final ProjectMetadataService projects;
    private final ConversationProjectService conversations;

    public ProjectApiService(AuthContextService identity, IdempotentRequestService requests,
                             EnterpriseAuthorizationService authorization,
                             ProjectMetadataService projects, ConversationProjectService conversations) {
        this.identity = identity;
        this.requests = requests;
        this.authorization = authorization;
        this.projects = projects;
        this.conversations = conversations;
    }

    public ApiOperationResult create(String enterprise, CreateProjectInput input, HttpServletRequest request) {
        InputValidation.request(request, CreateProjectInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            authorization.lockAndRequire(current, "agent.run");
        }, () -> ApiOperationResult.of(201, projects.create(actor, input)));
    }

    public ApiOperationResult select(String enterprise, String conversation, SelectProjectInput input,
                                     HttpServletRequest request) {
        InputValidation.request(request, SelectProjectInput.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            conversations.authorize(current, conversation);
            projects.require(enterprise, current.userId(), input.projectId());
        }, () -> ApiOperationResult.of(200, conversations.select(actor, conversation, input.projectId(), revision)));
    }
}
