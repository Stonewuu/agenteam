package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.service.enterprise.OrganizationDependencies;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Set;

/**
 * 管理入口只读取实际未结束数量，不返回个人来源或待办正文。
 */
@Repository
public class TodoOrganizationMapper implements OrganizationDependencies {
    private final TodoTableMapper todos;

    public TodoOrganizationMapper(TodoTableMapper todos) {
        this.todos = todos;
    }

    @Override
    public Map<String, Long> role(String enterpriseId, String roleId) {
        return Map.of();
    }

    @Override
    public Map<String, Long> team(String enterpriseId, String teamId) {
        return Map.of("openTodos", todos.selectCount(open(enterpriseId).eq(TodoItemRow::getTeamId, teamId)));
    }

    public long ownersOutsideMembers(String enterprise, String team, Set<String> members) {
        return todos.selectCount(open(enterprise).eq(TodoItemRow::getTeamId, team)
            .notIn(!members.isEmpty(), TodoItemRow::getOwnerUserId, members));
    }

    public long ownersOutsideTeams(String enterprise, String owner, Set<String> teams) {
        return todos.selectCount(open(enterprise).eq(TodoItemRow::getOwnerUserId, owner)
            .isNotNull(TodoItemRow::getTeamId).notIn(!teams.isEmpty(), TodoItemRow::getTeamId, teams));
    }

    private LambdaQueryWrapper<TodoItemRow> open(String enterprise) {
        return Wrappers.<TodoItemRow>lambdaQuery().eq(TodoItemRow::getEnterpriseId, enterprise)
            .isNull(TodoItemRow::getDeletedAt).in(TodoItemRow::getStatus, "pending", "in_progress");
    }
}
