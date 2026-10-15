package com.stonewu.agenteam.service.collaboration;

import java.util.Map;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.request.CollaborationActionRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationInputRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationResultResendRequest;
import com.stonewu.agenteam.model.collaboration.response.CollaborationReplyModeView;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskDetail;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** 本人任务接口沿用既有登录、跨站请求保护和重复请求处理。 */
@Service
public class CollaborationApiService {
    private final AuthContextService identity;
    private final CollaborationAccessService access;
    private final CollaborationQueryService queries;
    private final CollaborationInputService inputs;
    private final CollaborationRetryService retries;
    private final IdempotentRequestService requests;
    private final ResourceJson json;
    private final CollaborationReplyModeService replyModes;
    private final CollaborationResultResendService resultResend;

    public CollaborationApiService(AuthContextService identity, CollaborationAccessService access, CollaborationQueryService queries,
            CollaborationInputService inputs, CollaborationRetryService retries, IdempotentRequestService requests, ResourceJson json,
            CollaborationReplyModeService replyModes, CollaborationResultResendService resultResend) {
        this.identity = identity;
        this.access = access;
        this.queries = queries;
        this.inputs = inputs;
        this.retries = retries;
        this.requests = requests;
        this.json = json;
        this.replyModes = replyModes;
        this.resultResend = resultResend;
    }

    public PageResponse<CollaborationTaskSummary> list(String enterprise, String conversation, String status, String query, String cursor, Integer limit, HttpServletRequest request) {
        return queries.list(actor(enterprise, request), conversation, status, query, cursor, limit);
    }

    public CollaborationTaskDetail detail(String enterprise, String id, HttpServletRequest request) {
        return queries.detail(actor(enterprise, request), id);
    }

    public CollaborationReplyModeView replyMode(String enterprise, String id, HttpServletRequest request) {
        return replyModes.get(actor(enterprise, request), id);
    }

    public ApiOperationResult replyMode(String enterprise, String id, CollaborationReplyModeRequest input, HttpServletRequest request) {
        InputValidation.request(request, CollaborationReplyModeRequest.class);
        return execute(enterprise, id, request, actor -> ApiOperationResult.of(200, json.object(json.tree(replyModes.save(actor, id, input)))));
    }

    public ApiOperationResult resendResult(String enterprise, String id, CollaborationResultResendRequest input, HttpServletRequest request) {
        InputValidation.request(request, CollaborationResultResendRequest.class);
        return execute(enterprise, id, request, actor -> ApiOperationResult.of(202, resultResend.send(actor, id, input)));
    }

    public PageResponse<Map<String, Object>> messages(String enterprise, String id, String cursor, Integer limit, HttpServletRequest request) {
        return queries.messages(actor(enterprise, request), id, cursor, limit);
    }

    public Map<String, Object> artifact(String enterprise, String task, String artifact, int version, int offset, int limit, HttpServletRequest request) {
        return queries.artifact(actor(enterprise, request), task, artifact, version, offset, limit);
    }

    public ApiOperationResult input(String enterprise, String task, CollaborationInputRequest input, HttpServletRequest request) {
        InputValidation.request(request, CollaborationInputRequest.class);
        var options = json.tree(new InputOptions(input.outputVisibility(), input.messageReferences() == null ? List.of() : input.messageReferences(),
            input.waivedRoleIds() == null ? List.of() : input.waivedRoleIds()));
        return execute(enterprise, task, request, actor -> ApiOperationResult.of(200, json.object(inputs.append(actor, task, input.expectedRevision(),
            operation(actor, input.requestId()), input.text(), null, options))));
    }

    public ApiOperationResult cancel(String enterprise, String task, CollaborationActionRequest input, HttpServletRequest request) {
        InputValidation.request(request, CollaborationActionRequest.class);
        return execute(enterprise, task, request, actor -> ApiOperationResult.of(202, json.object(inputs.cancel(actor, task, input.expectedRevision(),
            operation(actor, input.requestId()), input.reason()))));
    }

    public ApiOperationResult retry(String enterprise, String task, CollaborationActionRequest input, HttpServletRequest request) {
        InputValidation.request(request, CollaborationActionRequest.class);
        if (input.workItemId() == null || input.workItemId().isBlank()) {
            throw ApiException.invalidField("workItemId", "请选择需要重试的工作。");
        }
        return execute(enterprise, task, request, actor -> ApiOperationResult.of(202, json.object(retries.retry(actor, task, input.workItemId(), input.expectedRevision(),
            operation(actor, input.requestId())))));
    }

    private ApiOperationResult execute(String enterprise, String task, HttpServletRequest request, Function<AuthContext, ApiOperationResult> action) {
        var actor = actor(enterprise, request);
        return requests.execute(request, actor.user(), enterprise, Set.of("agent.run"),
            () -> access.owned(actor(enterprise, request), task), () -> action.apply(actor));
    }

    private AuthContext actor(String enterprise, HttpServletRequest request) {
        return identity.requirePermission(request.getSession(false), enterprise, "agent.run");
    }

    private String operation(AuthContext actor, String id) {
        return "http:" + actor.userId() + ":" + id;
    }

    private record InputOptions(String outputVisibility, List<String> messageReferences, List<String> waivedRoleIds) {
    }
}
