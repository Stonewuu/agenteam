package com.stonewu.agenteam.mapper.todo;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.todo.entity.TodoDefinition;
import com.stonewu.agenteam.model.todo.entity.TodoStatus;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 只转换有明确业务含义的字段，不从聊天内容推断负责人、状态或截止日期。
 */
@Component
public class TodoDefinitionMapper {
    public TodoDefinition definition(TodoWriteRequest input) {
        String title = ResourceInput.text(input.title(), "title", 200, true);
        if (title.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("title", Messages.text("error.todo.todoDefinitionMapper.definition.1"));
        }
        String description = ResourceInput.text(input.description(), "description", 5000, false);
        String owner = identifier(input.ownerUserId(), "ownerUserId");
        String team = input.teamId() == null ? null : identifier(input.teamId(), "teamId");
        if (!Set.of("normal", "high").contains(input.priority() == null ? "" : input.priority())) {
            throw ApiException.invalidField("priority", Messages.text("error.todo.todoDefinitionMapper.definition.2"));
        }
        LocalDate due = null;
        if (input.dueDate() != null) {
            try {
                if (!input.dueDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new DateTimeParseException(Messages.text("error.todo.todoDefinitionMapper.definition.3"), input.dueDate(), 0);
                }
                due = LocalDate.parse(input.dueDate());
                if (due.getYear() < 1000) {
                    throw new DateTimeParseException(Messages.text("error.todo.todoDefinitionMapper.definition.4"), input.dueDate(), 0);
                }
            } catch (DateTimeParseException invalid) {
                throw ApiException.invalidField("dueDate", Messages.text("error.todo.todoDefinitionMapper.definition.5"));
            }
        }
        return new TodoDefinition(title, description, owner, team, due, input.priority());
    }

    public String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.equals(
            value.trim()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField(field, Messages.text("error.todo.todoDefinitionMapper.identifier.1"));
        }
        return value;
    }

    public String reason(String value) {
        return ResourceInput.text(value == null ? "" : value, "reason", 500, false);
    }

    public TodoStatus status(String value) {
        try {
            return TodoStatus.from(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidField("status", Messages.text("error.todo.todoDefinitionMapper.status.1"));
        }
    }
}
