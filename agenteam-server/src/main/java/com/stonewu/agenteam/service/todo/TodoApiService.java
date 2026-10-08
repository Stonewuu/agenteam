package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoTransferRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.service.audit.AuditEventService;
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
 * 复用原有写请求处理，重复请求仍需当前身份和待办操作资格。
 */
@Service
public class TodoApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final TodoPolicy policy;
    private final TodoManagementService management;
    private final AuditEventService audit;

    public TodoApiService(AuthContextService identity, IdempotentRequestService requests, TodoPolicy policy,
                          TodoManagementService management, AuditEventService audit) {
        this.identity = identity;
        this.requests = requests;
        this.policy = policy;
        this.management = management;
        this.audit = audit;
    }

    public ApiOperationResult create(String enterprise, TodoWriteRequest value, HttpServletRequest request) {
        InputValidation.request(request, TodoWriteRequest.class);
        return execute(enterprise, null, false, request, actor -> {
            var result = management.create(actor, value);
            record(actor, "todo.create", result, null);
            return ApiOperationResult.of(201, result);
        });
    }

    public ApiOperationResult update(String enterprise, String id, TodoWriteRequest value, HttpServletRequest request) {
        InputValidation.request(request, TodoWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request, actor -> {
            var result = management.update(actor, id, value, revision);
            record(actor, "todo.update", result, revision);
            return ApiOperationResult.of(200, result);
        });
    }

    public ApiOperationResult status(String enterprise, String id, TodoStatusRequest value,
                                     HttpServletRequest request) {
        InputValidation.request(request, TodoStatusRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request, actor -> {
            var result = management.status(actor, id, value, revision);
            record(actor, "todo.status", result, revision);
            return ApiOperationResult.of(200, result);
        });
    }

    public ApiOperationResult transfer(String enterprise, String id, TodoTransferRequest value,
                                       HttpServletRequest request) {
        InputValidation.request(request, TodoTransferRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request, actor -> {
            var result = management.transfer(actor, id, value, revision);
            record(actor, "todo.transfer", result, revision);
            return ApiOperationResult.of(200, result);
        });
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, true, request, actor -> {
            management.delete(actor, id, revision);
            audit.record(actor.enterpriseId(), actor.user(), "todo.delete", "todo", id, "删除待办", Map.of());
            return ApiOperationResult.of(200, Map.of());
        });
    }

    /**
     * 只在人工请求内记录实际变更；员工工具调用和未改变状态的操作不增加活动。
     */
    private void record(AuthContext actor, String action, TodoView result, Long previousRevision) {
        if (previousRevision != null && result.revision().equals(previousRevision.toString())) {
            return;
        }
        String summary = switch (action) {
            case "todo.create" -> "创建待办";
            case "todo.update" -> "修改待办内容";
            case "todo.status" -> "修改待办状态";
            case "todo.transfer" -> "转交待办";
            default -> throw new IllegalArgumentException("待办活动类型不正确");
        };
        audit.record(actor.enterpriseId(), actor.user(), action, "todo", result.id(), summary, Map.of());
    }

    private ApiOperationResult execute(String enterprise, String id, boolean deleted, HttpServletRequest request,
                                       Function<AuthContext, ApiOperationResult> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            policy.mutation(current);
            if (id != null) {
                policy.editable(current, id, false, deleted);
            }
        }, () -> action.apply(actor));
    }
}
