package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.todo.TodoDefinitionMapper;
import com.stonewu.agenteam.mapper.todo.TodoMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import com.stonewu.agenteam.model.todo.entity.TodoStatus;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoTransferRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 内容、负责人、状态和历史共同提交；站内通知只排入同一业务事务。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TodoManagementService {
    private final TodoPolicy policy;
    private final TodoMapper todos;
    private final TodoDefinitionMapper input;
    private final TodoSourceService sources;
    private final TodoQueryService queries;
    private final TodoHistoryService history;
    private final TodoAssignmentNotificationService notifications;
    private final Clock clock;

    public TodoManagementService(TodoPolicy policy, TodoMapper todos, TodoDefinitionMapper input,
                                 TodoSourceService sources, TodoQueryService queries,
                                 TodoHistoryService history, TodoAssignmentNotificationService notifications,
                                 Clock clock) {
        this.policy = policy;
        this.todos = todos;
        this.input = input;
        this.sources = sources;
        this.queries = queries;
        this.history = history;
        this.notifications = notifications;
        this.clock = clock;
    }

    public TodoView create(AuthContext actor, TodoWriteRequest request) {
        policy.mutation(actor);
        var definition = policy.definition(actor, input.definition(request), null);
        var source = sources.create(actor, request);
        String id = UUID.randomUUID().toString();
        todos.create(id, actor.enterpriseId(), actor.userId(), definition, source, clock.instant());
        var value = current(actor, id);
        history.record(actor, "create", null, value, "");
        notifications.assigned(actor, value, true);
        return queries.view(actor, value);
    }

    public TodoView update(AuthContext actor, String id, TodoWriteRequest request, long revision) {
        policy.mutation(actor);
        var before = policy.editable(actor, id, true, false);
        policy.revision(before, revision);
        sources.unchanged(before.source(), request);
        var definition = policy.definition(actor, input.definition(request), before);
        todos.update(before, definition, clock.instant());
        var value = current(actor, id);
        history.record(actor, "update", before, value, "");
        if (!before.ownerUserId().equals(value.ownerUserId())) {
            notifications.assigned(actor, value, false);
        }
        return queries.view(actor, value);
    }

    public TodoView status(AuthContext actor, String id, TodoStatusRequest request, long revision) {
        policy.mutation(actor);
        var before = policy.editable(actor, id, true, false);
        policy.revision(before, revision);
        TodoStatus target = input.status(request.status());
        String reason = input.reason(request.reason());
        if (!before.status().canChangeTo(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "TODO_STATUS_CONFLICT",
                "当前状态不能执行该操作，请重新加载待办。");
        }
        if (before.status() == target) {
            return queries.view(actor, before);
        }
        todos.status(before, target, clock.instant());
        var value = current(actor, id);
        String action = switch (target) {
            case PENDING -> "reopen";
            case IN_PROGRESS -> "start";
            case COMPLETED -> "complete";
            case CANCELLED -> "cancel";
        };
        history.record(actor, action, before, value, reason);
        return queries.view(actor, value);
    }

    public TodoView transfer(AuthContext actor, String id, TodoTransferRequest request, long revision) {
        policy.mutation(actor);
        var before = policy.editable(actor, id, true, false);
        policy.revision(before, revision);
        String owner = policy.assignee(actor, input.identifier(request.ownerUserId(), "ownerUserId"),
            before.teamId()), reason = input.reason(request.reason());
        if (before.ownerUserId().equals(owner)) {
            return queries.view(actor, before);
        }
        todos.transfer(before, owner, clock.instant());
        var value = current(actor, id);
        history.record(actor, "transfer", before, value, reason);
        notifications.assigned(actor, value, false);
        return queries.view(actor, value);
    }

    public void delete(AuthContext actor, String id, long revision) {
        policy.mutation(actor);
        var before = policy.editable(actor, id, true, false);
        policy.revision(before, revision);
        todos.delete(before, clock.instant());
        history.record(actor, "delete", before, current(actor, id), "");
    }

    private TodoRecord current(AuthContext actor, String id) {
        return todos.current(actor.enterpriseId(), id)
            .orElseThrow(() -> new IllegalStateException("待办修改后无法读取"));
    }
}
