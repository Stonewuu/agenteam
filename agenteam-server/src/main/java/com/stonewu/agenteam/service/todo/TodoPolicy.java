package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.todo.TodoMapper;
import com.stonewu.agenteam.mapper.todo.TodoRelationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.todo.entity.TodoAccessScope;
import com.stonewu.agenteam.model.todo.entity.TodoDefinition;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import com.stonewu.agenteam.model.todo.entity.TodoStatus;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 本人归属和团队资格分别检查，企业管理权限不能直接读取个人待办。
 */
@Service
public class TodoPolicy {
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final TodoMapper todos;
    private final TodoRelationMapper relations;

    public TodoPolicy(EnterpriseAuthorizationService authorization, PermissionMapper permissions, TodoMapper todos,
                      TodoRelationMapper relations) {
        this.authorization = authorization;
        this.permissions = permissions;
        this.todos = todos;
        this.relations = relations;
    }

    public TodoAccessScope readScope(AuthContext actor) {
        authorization.require(actor, "todo.view");
        return new TodoAccessScope(actor.enterpriseId(), actor.userId(), shared(actor, "todo.team_view"));
    }

    public TodoAccessScope writeScope(AuthContext actor) {
        authorization.require(actor, "todo.manage");
        return new TodoAccessScope(actor.enterpriseId(), actor.userId(), shared(actor, "todo.team_manage"));
    }

    public void mutation(AuthContext actor) {
        authorization.lockAndRequire(actor, "todo.manage");
    }

    public TodoRecord read(AuthContext actor, String id) {
        return todos.find(readScope(actor), id, false, false).orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public TodoRecord editable(AuthContext actor, String id, boolean lock, boolean deleted) {
        return todos.find(writeScope(actor), id, lock, deleted).orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public void teamList(AuthContext actor) {
        authorization.require(actor, "todo.team_view");
    }

    public String team(AuthContext actor, String id) {
        return relations.activeTeamId(actor.enterpriseId(), id, actor.userId())
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public String assignee(AuthContext actor, String owner, String team) {
        String user = relations.activeMemberId(actor.enterpriseId(), owner)
            .orElseThrow(() -> ApiException.invalidField("ownerUserId", "请选择当前企业的有效成员。"));
        if (team != null && !relations.activeTeamMember(actor.enterpriseId(), team, owner)) {
            throw ApiException.invalidField("ownerUserId", "负责人需要属于所选团队。");
        }
        return user;
    }

    public TodoDefinition definition(AuthContext actor, TodoDefinition value, TodoRecord before) {
        String owner = assignee(actor, value.ownerUserId(), value.teamId());
        String team = destinationTeam(actor, value.teamId(), before);
        return new TodoDefinition(value.title(), value.description(), owner, team, value.dueDate(), value.priority());
    }

    public String destinationTeam(AuthContext actor, String team, TodoRecord before) {
        if (team != null) {
            team = team(actor, team);
            if (before == null || !Objects.equals(before.teamId(), team)) {
                authorization.require(actor, "todo.team_manage");
            }
        } else if (before != null && before.teamId() != null && !related(actor, before)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有创建人或负责人可以把团队待办改为个人待办。");
        }
        return team;
    }

    public void teamChoices(AuthContext actor, boolean assign) {
        authorization.require(actor, assign ? "todo.manage" : "todo.view");
        authorization.require(actor, assign ? "todo.team_manage" : "todo.team_view");
    }

    public void revision(TodoRecord value, long expected) {
        if (value.revision() != expected) {
            throw ApiException.versionConflict(value.revision());
        }
    }

    public boolean canManage(AuthContext actor, TodoRecord value) {
        if (value.deletedAt() != null || permissions.operationScope(actor.userId(), actor.enterpriseId(), "todo.manage")
            .isEmpty()) {
            return false;
        }
        if (value.teamId() != null && !relations.activeTeamMember(actor.enterpriseId(), value.teamId(),
            actor.userId())) {
            return false;
        }
        return related(actor, value) || (value.teamId() != null && shared(actor, "todo.team_manage"));
    }

    public List<String> actions(AuthContext actor, TodoRecord value) {
        if (!canManage(actor, value)) {
            return List.of();
        }
        var result = new ArrayList<>(List.of("edit", "transfer", "delete"));
        if (value.status() == TodoStatus.PENDING) {
            result.add("start");
        }
        if (value.status().open()) {
            result.add("complete");
            result.add("cancel");
        } else {
            result.add("reopen");
        }
        return List.copyOf(result);
    }

    private boolean shared(AuthContext actor, String operation) {
        return permissions.operationScope(actor.userId(), actor.enterpriseId(), operation)
            .filter(scope -> scope != DataScope.OWN).isPresent();
    }

    private boolean related(AuthContext actor, TodoRecord value) {
        return value.createdBy().equals(actor.userId()) || value.ownerUserId().equals(actor.userId());
    }
}
