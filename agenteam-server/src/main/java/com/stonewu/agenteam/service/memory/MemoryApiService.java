package com.stonewu.agenteam.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.memory.request.MemoryWriteRequest;
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
 * 请求结果只存偏好编号，重放时重新读本人当前内容，删除后不能复现旧正文。
 */
@Service
public class MemoryApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final MemoryPolicy policy;
    private final MemoryService memories;
    private final ObjectMapper json;

    public MemoryApiService(AuthContextService identity, IdempotentRequestService requests, MemoryPolicy policy,
                            MemoryService memories, ObjectMapper json) {
        this.identity = identity;
        this.requests = requests;
        this.policy = policy;
        this.memories = memories;
        this.json = json;
    }

    public ApiOperationResult create(String enterprise, String agent, MemoryWriteRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, MemoryWriteRequest.class);
        return execute(enterprise, agent, request, true,
            actor -> ApiOperationResult.of(201, Map.of("memoryId", memories.create(actor, agent, value).id())));
    }

    public ApiOperationResult update(String enterprise, String agent, String id, MemoryWriteRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, MemoryWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, agent, request, true, actor -> ApiOperationResult.of(200,
            Map.of("memoryId", memories.update(actor, agent, id, value, revision).id())));
    }

    public ApiOperationResult delete(String enterprise, String agent, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, agent, request, false, actor -> {
            memories.delete(actor, agent, id, revision);
            return ApiOperationResult.of(200, Map.of());
        });
    }

    public ApiOperationResult clear(String enterprise, String agent, HttpServletRequest request) {
        return execute(enterprise, agent, request, false, actor -> {
            memories.clear(actor, agent);
            return ApiOperationResult.of(200, Map.of());
        });
    }

    private ApiOperationResult execute(String enterprise, String agent, HttpServletRequest request, boolean readResult,
                                       Function<AuthContext, ApiOperationResult> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        var stored = requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> policy.mutation(identity.requireEnterprise(request.getSession(false), enterprise), agent),
            () -> action.apply(actor));
        if (!readResult) {
            return stored;
        }
        String id = json.valueToTree(stored.data()).path("memoryId").asText();
        return new ApiOperationResult(stored.status(), memories.get(actor, agent, id), stored.replayed());
    }
}
