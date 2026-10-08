package com.stonewu.agenteam.controller.todo;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoTransferRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.todo.response.TodoHistoryView;
import com.stonewu.agenteam.model.todo.response.TodoOptionView;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.todo.TodoApiService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import com.stonewu.agenteam.service.todo.TodoSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 控制器只适配请求，待办的范围、来源和状态变更交由服务处理。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/todos")
public class TodoApiController {

    private final AuthContextService identity;

    private final TodoQueryService queries;

    private final TodoApiService changes;

    private final ApiResponses responses;

    private final TodoSelectionService selections;

    public TodoApiController(AuthContextService identity, TodoQueryService queries, TodoApiService changes,
                             ApiResponses responses, TodoSelectionService selections) {
        this.identity = identity;
        this.queries = queries;
        this.changes = changes;
        this.responses = responses;
        this.selections = selections;
    }

    @GetMapping("/teams")
    public ApiResponse<PageResponse<TodoOptionView>> teams(@PathVariable String enterpriseId,
                                                           @RequestParam(required = false) String purpose,
                                                           @RequestParam(required = false) String query,
                                                           @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit,
                                                           HttpServletRequest request) {
        return responses.success(
            selections.teams(identity.requireEnterprise(request.getSession(false), enterpriseId), purpose, query,
                cursor, limit), request);
    }

    @GetMapping("/assignees")
    public ApiResponse<PageResponse<TodoOptionView>> assignees(@PathVariable String enterpriseId,
                                                               @RequestParam(required = false) String todoId,
                                                               @RequestParam(required = false) String teamId,
                                                               @RequestParam(required = false) String query,
                                                               @RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer limit,
                                                               HttpServletRequest request) {
        return responses.success(
            selections.assignees(identity.requireEnterprise(request.getSession(false), enterpriseId), todoId, teamId,
                query, cursor, limit), request);
    }

    @GetMapping
    public ApiResponse<PageResponse<TodoView>> list(@PathVariable String enterpriseId,
                                                    @RequestParam(required = false) String scope,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String teamId,
                                                    @RequestParam(required = false) String query,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit,
                                                    HttpServletRequest request) {
        return responses.success(
            queries.list(identity.requireEnterprise(request.getSession(false), enterpriseId), scope, status, teamId,
                query, cursor, limit), request);
    }

    @GetMapping("/{todoId}")
    public ApiResponse<TodoView> get(@PathVariable String enterpriseId, @PathVariable String todoId,
                                     HttpServletRequest request) {
        return responses.success(
            queries.get(identity.requireEnterprise(request.getSession(false), enterpriseId), todoId), request);
    }

    @GetMapping("/{todoId}/history")
    public ApiResponse<PageResponse<TodoHistoryView>> history(@PathVariable String enterpriseId,
                                                              @PathVariable String todoId,
                                                              @RequestParam(required = false) String cursor,
                                                              @RequestParam(required = false) Integer limit,
                                                              HttpServletRequest request) {
        return responses.success(
            queries.history(identity.requireEnterprise(request.getSession(false), enterpriseId), todoId, cursor, limit),
            request);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody TodoWriteRequest value,
                                    HttpServletRequest request) {
        return responses.operation(changes.create(enterpriseId, value, request), request);
    }

    @PutMapping("/{todoId}")
    public ResponseEntity<?> update(@PathVariable String enterpriseId, @PathVariable String todoId,
                                    @RequestBody TodoWriteRequest value, HttpServletRequest request) {
        return responses.operation(changes.update(enterpriseId, todoId, value, request), request);
    }

    @PatchMapping("/{todoId}/status")
    public ResponseEntity<?> status(@PathVariable String enterpriseId, @PathVariable String todoId,
                                    @RequestBody TodoStatusRequest value, HttpServletRequest request) {
        return responses.operation(changes.status(enterpriseId, todoId, value, request), request);
    }

    @PostMapping("/{todoId}/transfer")
    public ResponseEntity<?> transfer(@PathVariable String enterpriseId, @PathVariable String todoId,
                                      @RequestBody TodoTransferRequest value, HttpServletRequest request) {
        return responses.operation(changes.transfer(enterpriseId, todoId, value, request), request);
    }

    @DeleteMapping("/{todoId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String todoId,
                                    HttpServletRequest request) {
        return responses.operation(changes.delete(enterpriseId, todoId, request), request);
    }
}
