package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.todo.TodoHistoryMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只保存必要变更摘要与用户说明，不复制来源会话或旧待办正文。
 */
@Service
public class TodoHistoryService {
    private final TodoHistoryMapper history;
    private final ResourceJson json;
    private final Clock clock;

    public TodoHistoryService(TodoHistoryMapper history, ResourceJson json, Clock clock) {
        this.history = history;
        this.json = json;
        this.clock = clock;
    }

    public void record(AuthContext actor, String action, TodoRecord before, TodoRecord after, String reason) {
        var result = summary(after);
        result.put("summary", actionText(action, before, after));
        result.put("reason", reason);
        if (before != null) {
            result.put("descriptionChanged", !before.description().equals(after.description()));
        }
        history.append(actor.enterpriseId(), after.id(), actor.userId(), action,
            before == null ? null : json.tree(summary(before)), json.tree(result), clock.instant());
    }

    private Map<String, Object> summary(TodoRecord value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", value.title());
        result.put("ownerUserId", value.ownerUserId());
        result.put("ownerName", value.ownerName());
        result.put("teamId", value.teamId());
        result.put("teamName", value.teamName());
        result.put("dueDate", value.dueDate() == null ? null : value.dueDate().toString());
        result.put("priority", value.priority());
        result.put("status", value.status().code());
        result.put("revision", value.revision());
        result.put("deleted", value.deletedAt() != null);
        return result;
    }

    private String actionText(String action, TodoRecord before, TodoRecord value) {
        return switch (action) {
            case "create" -> Messages.text("text.todo.todoHistoryService.actionText.1", value.title());
            case "update" -> before != null && !before.ownerUserId()
                .equals(value.ownerUserId()) ? Messages.text("text.todo.todoHistoryService.actionText.2", value.ownerName()) : Messages.text("text.todo.todoApiService.record.1");
            case "transfer" -> Messages.text("text.todo.todoHistoryService.actionText.3", value.ownerName());
            case "start" -> Messages.text("text.todo.todoHistoryService.actionText.4");
            case "complete" -> Messages.text("text.todo.todoHistoryService.actionText.5");
            case "cancel" -> Messages.text("text.todo.todoHistoryService.actionText.6");
            case "reopen" -> Messages.text("text.todo.todoHistoryService.actionText.7");
            case "delete" -> Messages.text("text.tool.toolDisplayNameMapper.BUILTINS.13");
            default -> throw new IllegalArgumentException(Messages.text("error.todo.todoHistoryService.actionText.1"));
        };
    }
}
