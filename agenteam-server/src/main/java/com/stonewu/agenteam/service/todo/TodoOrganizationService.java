package com.stonewu.agenteam.service.todo;

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
            throw pending(count, "待移出的成员仍负责 " + count + " 项未结束待办，请先转交、完成或取消这些待办。");
        }
    }

    public void requireMemberTeamsIncluded(String enterprise, String owner, Set<String> teams) {
        long count = todos.ownersOutsideTeams(enterprise, owner, teams);
        if (count > 0) {
            throw pending(count, "该成员仍负责 " + count + " 项未结束的团队待办，请先转交、完成或取消后再移出团队。");
        }
    }

    private ApiException pending(long count, String message) {
        return new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_EXIST", message,
            Map.of("counts", Map.of("openTodos", count)), Map.of());
    }
}
