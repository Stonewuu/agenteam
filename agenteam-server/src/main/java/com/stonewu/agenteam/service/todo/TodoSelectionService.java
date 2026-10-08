package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.todo.TodoOptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.todo.entity.TodoOptionRecord;
import com.stonewu.agenteam.model.todo.response.TodoOptionView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 选择人员和团队只检查待办操作资格，不借用组织管理查看权限。
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TodoSelectionService {
    private final TodoPolicy policy;
    private final TodoOptionMapper options;
    private final ListPagination pagination;

    public TodoSelectionService(TodoPolicy policy, TodoOptionMapper options, ListPagination pagination) {
        this.policy = policy;
        this.options = options;
        this.pagination = pagination;
    }

    public PageResponse<TodoOptionView> teams(AuthContext actor, String purpose, String query, String cursor,
                                              Integer count) {
        String use = purpose == null ? "filter" : purpose;
        if (!Set.of("filter", "assign").contains(use)) {
            throw ApiException.invalidField("purpose", "请选择团队筛选或分配。");
        }
        policy.teamChoices(actor, use.equals("assign"));
        String search = pagination.query(query);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "todo-teams", use + "|" + search,
            "created_desc");
        return page(
            options.teams(actor.enterpriseId(), actor.userId(), search, pagination.read(cursor, binding), limit), limit,
            binding);
    }

    public PageResponse<TodoOptionView> assignees(AuthContext actor, String todoId, String teamId, String query,
                                                  String cursor, Integer count) {
        policy.writeScope(actor);
        var before = todoId == null ? null : policy.editable(actor, todoId, false, false);
        teamId = policy.destinationTeam(actor, teamId, before);
        String search = pagination.query(query);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "todo-assignees",
            todoId + "|" + teamId + "|" + search, "joined_desc");
        return page(options.assignees(actor.enterpriseId(), teamId, search, pagination.read(cursor, binding), limit),
            limit, binding);
    }

    private PageResponse<TodoOptionView> page(List<TodoOptionRecord> rows, int limit, ListPagination.Binding binding) {
        var page = pagination.page(rows, limit, binding, row -> new PagePosition(row.createdAt(), row.id()));
        return new PageResponse<>(page.items().stream().map(row -> new TodoOptionView(row.id(), row.name())).toList(),
            page.nextCursor(), page.hasMore());
    }
}
