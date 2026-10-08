package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.request.PreviewInput;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 预览也使用一次请求一次提交，固定配置只进入本次执行。
 */
@Service
public class AgentPreviewApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final ResourcePolicy resources;
    private final RunSubmissionService submissions;

    public AgentPreviewApiService(AuthContextService identity, IdempotentRequestService requests,
                                  ResourcePolicy resources, RunSubmissionService submissions) {
        this.identity = identity;
        this.requests = requests;
        this.resources = resources;
        this.submissions = submissions;
    }

    public ApiOperationResult preview(String enterprise, String agent, PreviewInput input, HttpServletRequest request) {
        InputValidation.request(request, PreviewInput.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> resources.authorize(identity.requireEnterprise(request.getSession(false), enterprise), agent,
                "preview", true, false),
            () -> {
                resources.revision(resources.authorize(actor, agent, "preview", true, false), revision);
                return ApiOperationResult.of(202, submissions.preview(actor, agent, input));
            });
    }
}
