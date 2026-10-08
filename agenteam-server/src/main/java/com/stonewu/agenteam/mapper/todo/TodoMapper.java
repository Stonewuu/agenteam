package com.stonewu.agenteam.mapper.todo;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.todo.entity.*;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 待办先限制当前归属与团队成员资格，再搜索、排序和分页。
 */
@Repository
public class TodoMapper {
    private final TodoSqlMapper statements;

    public TodoMapper(TodoSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<TodoRecord> find(TodoAccessScope scope, String id, boolean lock, boolean deleted) {
        return statements.findTodo(scope, id, lock, deleted).stream().map(this::map).findFirst();
    }

    public Optional<TodoRecord> current(String enterprise, String id) {
        return statements.currentTodoItem(enterprise, id).stream().map(this::map).findFirst();
    }

    public List<TodoRecord> ownedOpen(String enterprise, String user) {
        return statements.ownedOpenTodoItem(enterprise, user).stream().map(this::map).toList();
    }

    public List<TodoRecord> list(TodoAccessScope access, String scope, String state, String team, String query,
                                 PagePosition after, int limit) {
        return statements.listTodos(access, scope, state, team, query, after, limit + 1).stream().map(this::map)
            .toList();
    }

    public void create(String id, String enterprise, String creator, TodoDefinition value, TodoSource source,
                       Instant now) {
        statements.createTodoItem(id, enterprise, value.title(), value.description(), creator, value.ownerUserId(),
            value.teamId(), value.dueDate(), value.priority(), source.type(), source.conversationId(),
            source.messageId(), source.runId(), timestamp(now));
    }

    public List<TodoRecord> recentOwnedOpen(TodoAccessScope scope) {
        return statements.recentOwnedTodos(scope).stream().map(this::map).toList();
    }

    public long countOwnedOpen(TodoAccessScope scope) {
        return statements.countOwnedTodos(scope);
    }

    public void update(TodoRecord before, TodoDefinition value, Instant now) {
        statements.updateTodoItem(value.title(), value.description(), value.ownerUserId(), value.teamId(),
            value.dueDate(), value.priority(), timestamp(now), before.enterpriseId(), before.id());
    }

    public void status(TodoRecord before, TodoStatus status, Instant now) {
        statements.statusTodoItem(status.code(), status == TodoStatus.COMPLETED ? timestamp(now) : null, timestamp(now),
            before.enterpriseId(), before.id());
    }

    public void transfer(TodoRecord before, String owner, Instant now) {
        statements.transferTodoItem(owner, timestamp(now), before.enterpriseId(), before.id());
    }

    public void delete(TodoRecord before, Instant now) {
        statements.deleteTodoItem(timestamp(now), before.enterpriseId(), before.id());
    }

    private TodoRecord map(TodoQueryRow rows) {
        return new TodoRecord(rows.getId(), rows.getEnterpriseId(), rows.getTitle(), rows.getDescription(),
            rows.getCreatedBy(), rows.getOwnerUserId(),
            rows.getTeamId(), rows.getDueDate(), rows.getPriority(), TodoStatus.from(rows.getStatus()),
            new TodoSource(rows.getSourceType(), rows.getSourceConversationId(), rows.getSourceMessageId(),
                rows.getSourceRunId()),
            instant(rows.getCompletedAt()), instant(rows.getDeletedAt()), rows.getRevision(),
            instant(rows.getCreatedAt()), instant(rows.getUpdatedAt()),
            rows.getCreatorName(), rows.getOwnerName(), rows.getTeamName());
    }
}
