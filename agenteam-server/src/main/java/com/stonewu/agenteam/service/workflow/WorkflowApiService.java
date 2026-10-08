package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.mapper.workflow.WorkflowValidationMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.workflow.request.WorkflowPreviewInput;
import com.stonewu.agenteam.model.workflow.response.WorkflowValidationView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 按当前授权校验草稿或提交独立测试，不覆盖资源中保存的草稿。
 */
@Service
public class WorkflowApiService {
    private final AuthContextService identity;
    private final ResourcePolicy resources;
    private final WorkflowPreparationService preparation;
    private final WorkflowValidationMapper validation;
    private final IdempotentRequestService requests;
    private final RunSubmissionService submissions;

    public WorkflowApiService(AuthContextService identity, ResourcePolicy resources,
                              WorkflowPreparationService preparation, WorkflowValidationMapper validation,
                              IdempotentRequestService requests, RunSubmissionService submissions) {
        this.identity = identity;
        this.resources = resources;
        this.preparation = preparation;
        this.validation = validation;
        this.requests = requests;
        this.submissions = submissions;
    }

    public WorkflowValidationView validate(String enterprise, String resourceId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        var resource = resources.authorize(actor, resourceId, "edit", false, false);
        if (resource.kind() != ResourceKind.WORKFLOW) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (!(request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE) instanceof JsonNode config)) {
            throw ApiException.invalidField("body", "请提交完整的工作流配置。");
        }
        try {
            preparation.prepare(actor, resource, config);
            return new WorkflowValidationView(true, List.of());
        } catch (ApiException error) {
            if (!Set.of(409, 422).contains(error.getStatusCode().value())) {
                throw error;
            }
            return validation.invalid(error, config);
        }
    }

    public ApiOperationResult preview(String enterprise, String resourceId, HttpServletRequest request) {
        InputValidation.request(request, WorkflowPreviewInput.class);
        long revision = RequestPreconditions.revision(request);
        var body = (JsonNode) request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE);
        var input = new WorkflowPreviewInput(body.path("draft"), body.path("input"));
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> resources.authorize(identity.requireEnterprise(request.getSession(false), enterprise), resourceId,
                "preview", true, false),
            () -> {
                resources.revision(resources.authorize(actor, resourceId, "preview", true, false), revision);
                return ApiOperationResult.of(202, submissions.workflowPreview(actor, resourceId, input));
            });
    }
}
