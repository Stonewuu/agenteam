package com.stonewu.agenteam.service.todo;

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
            case "create" -> "创建待办“" + value.title() + "”";
            case "update" -> before != null && !before.ownerUserId()
                .equals(value.ownerUserId()) ? "修改待办并转交给“" + value.ownerName() + "”" : "修改待办内容";
            case "transfer" -> "将待办转交给“" + value.ownerName() + "”";
            case "start" -> "开始处理待办";
            case "complete" -> "完成待办";
            case "cancel" -> "取消待办";
            case "reopen" -> "重新打开待办";
            case "delete" -> "删除待办";
            default -> throw new IllegalArgumentException("待办历史动作不正确");
        };
    }
}
