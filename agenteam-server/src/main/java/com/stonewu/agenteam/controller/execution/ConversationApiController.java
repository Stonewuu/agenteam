package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.ConversationApiService;
import com.stonewu.agenteam.service.execution.ConversationModelService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.execution.ConversationSnapshotService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 正式会话接口只适配请求与响应，提交后由后台工作进程执行。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ConversationApiController {

    private final ConversationApiService mutations;

    private final ConversationQueryService queries;

    private final AuthContextService identity;

    private final ApiResponses responses;

    private final ConversationModelService models;

    private final ConversationSnapshotService snapshots;

    public ConversationApiController(ConversationApiService mutations, ConversationQueryService queries,
                                     AuthContextService identity, ApiResponses responses,
                                     ConversationModelService models, ConversationSnapshotService snapshots) {
        this.mutations = mutations;
        this.queries = queries;
        this.identity = identity;
        this.responses = responses;
        this.models = models;
        this.snapshots = snapshots;
    }

    @GetMapping("/agents/{agentId}/model-options")
    public ApiResponse<?> models(@PathVariable String enterpriseId, @PathVariable String agentId,
                                 @RequestParam(required = false) String conversationId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(models.options(actor, agentId, conversationId), request);
    }

    @PutMapping("/conversations/{conversationId}/model-selection")
    public ResponseEntity<?> selectModel(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                         @RequestBody ModelSelection value, HttpServletRequest request) {
        return responses.operation(mutations.selectModel(enterpriseId, conversationId, value, request), request);
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody NewConversationInput value,
                                    HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, value, request), request);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> send(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                  @RequestBody MessageInput value, HttpServletRequest request) {
        return responses.operation(mutations.send(enterpriseId, conversationId, value, request), request);
    }

    @GetMapping("/conversations")
    public ApiResponse<?> list(@PathVariable String enterpriseId, @RequestParam(required = false) String query,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) Boolean favorite,
                               @RequestParam(required = false) String agentId,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(queries.list(actor, query, status, favorite, agentId, cursor, limit), request);
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<?> snapshot(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                   HttpServletRequest request) {
        return responses.success(
            snapshots.read(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId),
            request);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<?> history(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                  @RequestParam(required = false) String beforeMessageId,
                                  @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(
            queries.history(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId,
                beforeMessageId, limit), request);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<?> run(@PathVariable String enterpriseId, @PathVariable String runId,
                              HttpServletRequest request) {
        return responses.success(
            queries.run(identity.requireEnterprise(request.getSession(false), enterpriseId), runId), request);
    }
}
