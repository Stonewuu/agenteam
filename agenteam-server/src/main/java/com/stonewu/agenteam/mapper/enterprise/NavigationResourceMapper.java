package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import org.springframework.stereotype.Repository;

/** 只读取页面定位必需的企业编号；返回给浏览器前仍须经过原业务授权。 */
@Repository
public class NavigationResourceMapper {
    private final ConversationSqlMapper conversations;
    private final TodoTableMapper todos;
    private final ScheduleSqlMapper schedules;
    private final CollaborationTaskMapper collaborations;
    private final NotificationSqlMapper notifications;
    private final ResourceSqlMapper resources;

    public NavigationResourceMapper(ConversationSqlMapper conversations, TodoTableMapper todos, ScheduleSqlMapper schedules,
                                    CollaborationTaskMapper collaborations, NotificationSqlMapper notifications,
                                    ResourceSqlMapper resources) {
        this.conversations = conversations;
        this.todos = todos;
        this.schedules = schedules;
        this.collaborations = collaborations;
        this.notifications = notifications;
        this.resources = resources;
    }

    public String enterprise(String type, String id, String user) {
        return switch (type) {
            case "conversation" -> {
                var row = conversations.selectOne(Wrappers.<AgentConversationRow>lambdaQuery()
                    .select(AgentConversationRow::getEnterpriseId).eq(AgentConversationRow::getId, id));
                yield row == null ? null : row.getEnterpriseId();
            }
            case "todo" -> {
                var row = todos.selectOne(Wrappers.<TodoItemRow>lambdaQuery().select(TodoItemRow::getEnterpriseId)
                    .eq(TodoItemRow::getId, id));
                yield row == null ? null : row.getEnterpriseId();
            }
            case "schedule" -> {
                var row = schedules.selectOne(Wrappers.<ScheduledTaskRow>lambdaQuery().select(ScheduledTaskRow::getEnterpriseId)
                    .eq(ScheduledTaskRow::getId, id));
                yield row == null ? null : row.getEnterpriseId();
            }
            case "collaboration" -> {
                var row = collaborations.selectOne(Wrappers.<CollaborationTaskRow>lambdaQuery()
                    .select(CollaborationTaskRow::getEnterpriseId).eq(CollaborationTaskRow::getId, id));
                yield row == null ? null : row.getEnterpriseId();
            }
            case "notification" -> {
                var row = notifications.selectOne(Wrappers.<NotificationRow>lambdaQuery()
                    .select(NotificationRow::getEnterpriseId).eq(NotificationRow::getId, id).eq(NotificationRow::getUserId, user));
                yield row == null ? null : row.getEnterpriseId();
            }
            case "agent", "skill", "plugin", "workflow", "knowledge", "data", "memory" -> {
                var row = resources.selectOne(Wrappers.<ResourceRow>lambdaQuery().select(ResourceRow::getEnterpriseId)
                    .eq(ResourceRow::getId, id).eq(ResourceRow::getKind, type.equals("memory") ? "agent" : type));
                yield row == null ? null : row.getEnterpriseId();
            }
            default -> null;
        };
    }
}
