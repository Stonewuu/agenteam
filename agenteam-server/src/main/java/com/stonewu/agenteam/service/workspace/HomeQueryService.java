package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.todo.TodoMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.model.workspace.response.HomeSummaryView;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.todo.TodoPolicy;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class HomeQueryService {
    private final EnterpriseAuthorizationService access;
    private final PermissionMapper permissions;
    private final ConversationQueryService conversations;
    private final EmployeeQueryService employees;
    private final TodoPolicy todoPolicy;
    private final TodoMapper todos;
    private final TodoQueryService todoQueries;
    private final ScheduleMapper schedules;

    public HomeQueryService(EnterpriseAuthorizationService access, PermissionMapper permissions,
                            ConversationQueryService conversations,
                            EmployeeQueryService employees, TodoPolicy todoPolicy, TodoMapper todos,
                            TodoQueryService todoQueries, ScheduleMapper schedules) {
        this.access = access;
        this.permissions = permissions;
        this.conversations = conversations;
        this.employees = employees;
        this.todoPolicy = todoPolicy;
        this.todos = todos;
        this.todoQueries = todoQueries;
        this.schedules = schedules;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HomeSummaryView get(AuthContext current) {
        access.require(current, "workspace.view");
        var codes = Set.copyOf(permissions.listPermissionCodes(current.userId(), current.enterpriseId()));
        var actor = new AuthContext(current.user(), current.enterpriseId(), codes);
        List<TodoView> recentTodos = List.of();
        long count = 0;
        if (codes.containsAll(List.of("todo.view", "todo.manage"))) {
            var scope = todoPolicy.readScope(actor);
            recentTodos = todos.recentOwnedOpen(scope).stream().map(row -> todoQueries.view(actor, row)).toList();
            count = todos.countOwnedOpen(scope);
        }
        return new HomeSummaryView(
            codes.contains("conversation.view") ? conversations.list(actor, null, "active", null, null, null, 3)
                .items() : List.of(),
            recentTodos, codes.contains("agent.run") ? employees.recentAvailable(actor) : List.of(), count,
            codes.contains("schedule.view") ? schedules.countEnabled(actor.enterpriseId(), actor.userId()) : 0);
    }
}
