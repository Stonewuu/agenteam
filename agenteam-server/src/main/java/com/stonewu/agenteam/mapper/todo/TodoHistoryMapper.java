package com.stonewu.agenteam.mapper.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.todo.entity.TodoHistoryRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 历史只追加，调用者必须先验证当前待办的查看权限。
 */
@Repository
public class TodoHistoryMapper {
    private final TodoHistorySqlMapper statements;
    private final ResourceJson json;

    public TodoHistoryMapper(TodoHistorySqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public void append(String enterprise, String todo, String actor, String action, JsonNode before, JsonNode after,
                       Instant now) {
        statements.appendTodoHistory(UUID.randomUUID().toString(), enterprise, todo, actor, action,
            before == null ? null : json.write(before), json.write(after), timestamp(now));
    }

    public List<TodoHistoryRecord> list(String enterprise, String todo, PagePosition after, int limit) {
        return statements.listHistory(enterprise, todo, after, limit + 1).stream().map(row ->
            new TodoHistoryRecord(row.getId(), row.getActorUserId(), row.getActorName(), row.getAction(),
                row.getBeforeJson() == null ? null : json.read(row.getBeforeJson()),
                json.read(row.getAfterJson()), instant(row.getCreatedAt()))).toList();
    }
}
