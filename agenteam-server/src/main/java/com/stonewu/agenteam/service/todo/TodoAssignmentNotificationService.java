package com.stonewu.agenteam.service.todo;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.todo.entity.TodoRecord;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import org.springframework.stereotype.Service;

/**
 * 普通转交和成员交接使用同一个通知编号，随待办事务一起保存。
 */
@Service
public class TodoAssignmentNotificationService {
    private final NotificationDeliveryService notifications;

    public TodoAssignmentNotificationService(NotificationDeliveryService notifications) {
        this.notifications = notifications;
    }

    public void assigned(AuthContext actor, TodoRecord value, boolean created) {
        if (actor.userId().equals(value.ownerUserId())) {
            return;
        }
        notifications.enqueue(actor.enterpriseId(), value.ownerUserId(),
            new Notice("todo:" + value.id() + ":assigned:" + value.revision(), "todo",
                created ? Messages.text("text.todo.todoAssignmentNotificationService.assigned.1") : Messages.text("text.todo.todoAssignmentNotificationService.assigned.2"), Messages.text("text.todo.todoAssignmentNotificationService.assigned.3", value.title()), "todo", value.id()));
    }
}
