package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.schedule.request.ScheduleRuleInput;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 参数省略时使用明确的业务默认值，不让模型提供执行身份或伪造来源。
 */
@Component
public class PlatformToolInputMapper {
    public TodoWriteRequest todo(BuiltinExecutionContext context, JsonNode args, TodoView previous) {
        var run = context.run();
        var request = new TodoWriteRequest(text(args, "title", previous == null ? "" : previous.title()),
            text(args, "description", previous == null ? "" : previous.description()),
            text(args, "ownerUserId", previous == null ? context.actor().userId() : previous.owner().id()),
            text(args, "teamId", previous == null ? null : previous.teamId()),
            text(args, "dueDate", previous == null ? null : previous.dueDate()),
            text(args, "priority", previous == null ? "normal" : previous.priority()),
            previous == null ? "message" : previous.sourceType(),
            previous == null ? run.conversationId() : null, previous == null ? run.outputMessageId() : null, null);
        InputValidation.validate(request);
        return request;
    }

    public ScheduleWriteRequest schedule(JsonNode args, String timezone) {
        var value = scheduleDefaults(args, timezone);
        value.remove(List.of("scheduleId", "revision"));
        if (!value.has("agentVersionId")) {
            value.putNull("agentVersionId");
        }
        if (!value.has("maxRetries")) {
            value.put("maxRetries", 0);
        }
        return InputValidation.read(value, ScheduleWriteRequest.class, "");
    }

    public ScheduleRuleInput rule(JsonNode args, String timezone) {
        return InputValidation.read(scheduleDefaults(args, timezone), ScheduleRuleInput.class, "");
    }

    public long revision(JsonNode args) {
        try {
            long value = Long.parseLong(args.path("revision").asText());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) { /* 下方返回统一字段提示。 */ }
        throw ApiException.invalidField("revision", "请先读取记录，使用有效的当前修改版本。");
    }

    public String text(JsonNode args, String key) {
        return text(args, key, null);
    }

    public String text(JsonNode args, String key, String fallback) {
        return args.has(key) ? args.path(key).asText(null) : fallback;
    }

    public int limit(JsonNode args) {
        return args.path("limit").asInt(10);
    }

    private ObjectNode scheduleDefaults(JsonNode args, String timezone) {
        ObjectNode value = args.deepCopy();
        if (!value.has("timezone")) {
            value.put("timezone", timezone);
        }
        if (!value.has("localDate")) {
            value.putNull("localDate");
        }
        if (!value.has("monthDay")) {
            value.putNull("monthDay");
        }
        if (!value.has("weekdays")) {
            value.putArray("weekdays");
        }
        return value;
    }
}
