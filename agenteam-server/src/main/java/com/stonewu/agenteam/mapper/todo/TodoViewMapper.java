package com.stonewu.agenteam.mapper.todo;

import com.stonewu.agenteam.model.enterprise.response.ActorView;
import com.stonewu.agenteam.model.todo.entity.TodoHistoryRecord;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import com.stonewu.agenteam.model.todo.response.TodoHistoryView;
import com.stonewu.agenteam.model.todo.response.TodoView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 只输出允许展示的待办字段，不序列化私有来源编号和历史内部摘要。
 */
@Component
public class TodoViewMapper {
    public TodoView view(TodoRecord value, boolean sourceAccessible, List<String> actions) {
        return new TodoView(value.id(), Long.toString(value.revision()), value.createdAt().toString(),
            value.updatedAt().toString(), value.title(), value.description(),
            new ActorView(value.ownerUserId(), value.ownerName()),
            new ActorView(value.createdBy(), value.creatorName()), value.teamId(), value.teamName(),
            value.dueDate() == null ? null : value.dueDate().toString(), value.priority(), value.status().code(),
            value.source().type(), sourceAccessible,
            sourceAccessible ? value.source().conversationId() : null,
            value.completedAt() == null ? null : value.completedAt().toString(), actions);
    }

    public TodoHistoryView history(TodoHistoryRecord value) {
        return new TodoHistoryView(value.id(), new ActorView(value.actorUserId(), value.actorName()), value.action(),
            value.after().required("summary").asText(),
            value.after().required("reason").asText(), value.createdAt().toString());
    }
}
