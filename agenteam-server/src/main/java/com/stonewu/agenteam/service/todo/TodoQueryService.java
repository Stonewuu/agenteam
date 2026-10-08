package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.todo.TodoHistoryMapper;
import com.stonewu.agenteam.mapper.todo.TodoMapper;
import com.stonewu.agenteam.mapper.todo.TodoViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import com.stonewu.agenteam.model.todo.response.TodoHistoryView;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class TodoQueryService {
    private final TodoPolicy policy;
    private final TodoMapper todos;
    private final TodoHistoryMapper history;
    private final TodoViewMapper views;
    private final TodoSourceService sources;
    private final ListPagination pagination;

    public TodoQueryService(TodoPolicy policy, TodoMapper todos, TodoHistoryMapper history, TodoViewMapper views,
                            TodoSourceService sources, ListPagination pagination) {
        this.policy = policy;
        this.todos = todos;
        this.history = history;
        this.views = views;
        this.sources = sources;
        this.pagination = pagination;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TodoView get(AuthContext actor, String id) {
        return view(actor, policy.read(actor, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<TodoView> list(AuthContext actor, String scope, String status, String team, String query,
                                       String cursor, Integer count) {
        var access = policy.readScope(actor);
        String area = scope == null ? "mine" : scope;
        String state = status == null ? "" : status;
        if (!Set.of("mine", "team").contains(area)) {
            throw ApiException.invalidField("scope", "请选择我的待办或团队待办。");
        }
        if (!Set.of("", "open", "pending", "in_progress", "completed", "cancelled").contains(state)) {
            throw ApiException.invalidField("status", "请选择有效的待办状态。");
        }
        if (area.equals("team")) {
            policy.teamList(actor);
        }
        if (team != null) {
            policy.team(actor, team);
        }
        String search = pagination.query(query);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "todos",
            area + "|" + state + "|" + team + "|" + search, "updated_desc");
        var page = pagination.page(
            todos.list(access, area, state, team, search, pagination.read(cursor, binding), limit), limit, binding,
            row -> new PagePosition(row.updatedAt(), row.id()));
        Map<String, Boolean> readable = new HashMap<>();
        return new PageResponse<>(page.items().stream().map(row -> views.view(row,
            row.source().conversationId() != null && readable.computeIfAbsent(row.source().conversationId(),
                ignored -> sources.accessible(actor, row.source())), policy.actions(actor, row))).toList(),
            page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<TodoHistoryView> history(AuthContext actor, String id, String cursor, Integer count) {
        policy.read(actor, id);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "todos/" + id + "/history", "",
            "created_desc");
        var page = pagination.page(history.list(actor.enterpriseId(), id, pagination.read(cursor, binding), limit),
            limit, binding, row -> new PagePosition(row.createdAt(), row.id()));
        return new PageResponse<>(page.items().stream().map(views::history).toList(), page.nextCursor(),
            page.hasMore());
    }

    public TodoView view(AuthContext actor, TodoRecord value) {
        return views.view(value, sources.accessible(actor, value.source()), policy.actions(actor, value));
    }
}
