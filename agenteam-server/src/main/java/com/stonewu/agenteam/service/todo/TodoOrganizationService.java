package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.todo.TodoOrganizationMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 组织变更沿用企业事务，未处理的负责人不能直接离开待办所属团队。
 */
@Service
public class TodoOrganizationService {
    private final TodoOrganizationMapper todos;

    public TodoOrganizationService(TodoOrganizationMapper todos) {
        this.todos = todos;
    }

    public void requireTeamOwnersIncluded(String enterprise, String team, Set<String> members) {
        long count = todos.ownersOutsideMembers(enterprise, team, members);
        if (count > 0) {
            throw pending(count, Messages.text("error.todo.todoOrganizationService.requireTeamOwnersIncluded.1", count));
        }
    }

    public void requireMemberTeamsIncluded(String enterprise, String owner, Set<String> teams) {
        long count = todos.ownersOutsideTeams(enterprise, owner, teams);
        if (count > 0) {
            throw pending(count, Messages.text("error.todo.todoOrganizationService.requireMemberTeamsIncluded.1", count));
        }
    }

    private ApiException pending(long count, String message) {
        return new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_EXIST", message,
            Map.of("counts", Map.of("openTodos", count)), Map.of());
    }
}
