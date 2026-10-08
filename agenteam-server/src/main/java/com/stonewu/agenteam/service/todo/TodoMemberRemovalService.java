package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.todo.TodoMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState.TodoItem;
import com.stonewu.agenteam.model.todo.entity.TodoStatus;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 成员移除可以按确认方案处理负责人待办，不向管理员返回私有正文或来源。
 */
@Service
public class TodoMemberRemovalService {
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final TodoMapper todos;
    private final TodoPolicy policy;
    private final TodoHistoryService history;
    private final TodoAssignmentNotificationService notifications;
    private final Clock clock;

    public TodoMemberRemovalService(EnterpriseAuthorizationService authorization, PermissionMapper permissions,
                                    TodoMapper todos, TodoPolicy policy, TodoHistoryService history,
                                    TodoAssignmentNotificationService notifications, Clock clock) {
        this.authorization = authorization;
        this.permissions = permissions;
        this.todos = todos;
        this.policy = policy;
        this.history = history;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void process(AuthContext actor, String owner, List<TodoItem> expected, String recipient, boolean cancel) {
        authorization.requireEnterpriseScope(authorization.lockAndRequire(actor, "enterprise.members.manage"));
        var rows = todos.ownedOpen(actor.enterpriseId(), owner);
        var actual = rows.stream().map(row -> new TodoItem(row.id(), row.revision(), row.teamId())).toList();
        if (!actual.equals(expected)) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_CHANGED", "未结束待办已变化，请重新查看移除影响。");
        }
        if (rows.isEmpty()) {
            return;
        }
        if (!cancel) {
            if (Objects.equals(owner, recipient) || recipient == null || permissions.operationScope(recipient,
                actor.enterpriseId(), "todo.manage").isEmpty()
                || permissions.operationScope(recipient, actor.enterpriseId(), "todo.view").isEmpty()) {
                throw ApiException.invalidField("todoOwnerId", "请选择仍可查看和处理本人待办的有效成员。");
            }
            for (var row : rows) {
                try {
                    policy.assignee(actor, recipient, row.teamId());
                } catch (ApiException invalid) {
                    throw ApiException.invalidField("todoOwnerId", invalid.getReason());
                }
            }
        }
        for (var before : rows) {
            if (cancel) {
                todos.status(before, TodoStatus.CANCELLED, clock.instant());
            } else {
                todos.transfer(before, recipient, clock.instant());
            }
            var after = todos.current(actor.enterpriseId(), before.id()).orElseThrow();
            history.record(actor, cancel ? "cancel" : "transfer", before, after,
                cancel ? "成员移除时取消未结束待办" : "成员移除时转交待办");
            if (!cancel) {
                notifications.assigned(actor, after, false);
            }
        }
    }
}
