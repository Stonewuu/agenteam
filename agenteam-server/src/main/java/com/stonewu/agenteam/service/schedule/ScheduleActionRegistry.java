package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 操作类型与实现一一对应，不允许请求指定任意类名、脚本或执行地址。 */
@Component
public class ScheduleActionRegistry {
    private final Map<String, ScheduleActionHandler> handlers;

    public ScheduleActionRegistry(List<ScheduleActionHandler> implementations) {
        var registered = new LinkedHashMap<String, ScheduleActionHandler>();
        for (var handler : implementations) {
            if (registered.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException(Messages.text("error.schedule.scheduleActionRegistry.init.1", handler.type()));
            }
        }
        handlers = Map.copyOf(registered);
    }

    public List<ScheduleActionHandler> available(AuthContext actor) {
        return handlers.values().stream().filter(handler -> handler.available(actor)).sorted((left, right) -> left.type().compareTo(right.type())).toList();
    }

    public ScheduleActionHandler require(String type, int schemaVersion) {
        var handler = type == null ? null : handlers.get(type);
        if (handler == null) {
            throw ApiException.invalidField("action.type", Messages.text("error.schedule.scheduleActionRegistry.require.1"));
        }
        if (!handler.schemaVersions().contains(schemaVersion)) {
            throw ApiException.invalidField("action.schemaVersion", Messages.text("error.schedule.scheduleActionRegistry.require.2"));
        }
        return handler;
    }

    public ScheduleActionDefinition prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous) {
        var action = input.action();
        if (action != null && (input.hireId() != null || input.agentVersionId() != null || input.inputText() != null)) {
            throw ApiException.invalidField("action", Messages.text("error.schedule.scheduleActionRegistry.prepare.1"));
        }
        if (action != null && action.schemaVersion() == null) {
            throw ApiException.invalidField("action.schemaVersion", Messages.text("error.schedule.scheduleActionRegistry.prepare.2"));
        }
        return require(action == null ? "agent.run" : action.type(), action == null ? 1 : action.schemaVersion()).prepare(actor, input, previous);
    }
}
