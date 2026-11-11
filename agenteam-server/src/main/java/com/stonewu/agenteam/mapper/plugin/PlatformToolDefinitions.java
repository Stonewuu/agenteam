package com.stonewu.agenteam.mapper.plugin;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台工具使用封闭参数结构，每个动作独立声明读写性质。
 */
@Component
public class PlatformToolDefinitions {
    private final ResourceJson json;

    public PlatformToolDefinitions(ResourceJson json) {
        this.json = json;
    }

    public List<ToolDefinition> basics() {
        return List.of(tool("platform_context", Messages.source("text.plugin.platformToolDefinitions.basics.1"), false, Map.of()),
            tool("platform_time", Messages.source("text.plugin.platformToolDefinitions.basics.2"), false,
                Map.of()),
            tool("platform_search", Messages.source("text.plugin.platformToolDefinitions.basics.3"), false,
                Map.of("query", text(100)), "query"));
    }

    public List<ToolDefinition> todos() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("todo_list", Messages.source("text.plugin.platformToolDefinitions.todos.1"), false,
            fields(page(), "scope", choice("mine", "team"), "status",
                choice("open", "pending", "in_progress", "completed", "cancelled"), "teamId", nullableText(100))));
        result.add(
            tool("todo_get", Messages.source("text.plugin.platformToolDefinitions.todos.2"), false, Map.of("todoId", text(100)),
                "todoId"));
        result.add(
            tool("todo_history", Messages.source("text.plugin.platformToolDefinitions.todos.3"), false, fields(page(), "todoId", text(100)), "todoId"));
        result.add(tool("todo_assignees", Messages.source("text.plugin.platformToolDefinitions.todos.4"), false,
            fields(page(), "todoId", nullableText(100), "teamId", nullableText(100))));
        result.add(tool("todo_teams", Messages.source("text.plugin.platformToolDefinitions.todos.5"), false,
            fields(page(), "purpose", choice("filter", "assign"))));
        result.add(
            tool("todo_create", Messages.source("text.plugin.platformToolDefinitions.todos.6"), true,
                todoFields(), "title"));
        result.add(
            tool("todo_update", Messages.source("text.plugin.platformToolDefinitions.todos.7"), true,
                fields(todoFields(), "todoId", text(100), "revision", revision()), "todoId", "revision"));
        result.add(tool("todo_set_status", Messages.source("text.plugin.platformToolDefinitions.todos.8"), true,
            fields(identity("todoId"), "status", choice("pending", "in_progress", "completed", "cancelled"), "reason",
                text(500)), "todoId", "revision", "status"));
        result.add(tool("todo_transfer", Messages.source("text.plugin.platformToolDefinitions.todos.9"), true,
            fields(identity("todoId"), "ownerUserId", text(100), "reason", text(500)), "todoId", "revision",
            "ownerUserId"));
        result.add(tool("todo_delete", Messages.source("text.plugin.platformToolDefinitions.todos.10"), "destructive", identity("todoId"), "todoId",
            "revision"));
        return List.copyOf(result);
    }

    public List<ToolDefinition> schedules() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("schedule_list", Messages.source("text.plugin.platformToolDefinitions.schedules.1"), false, page()));
        result.add(
            tool("schedule_get", Messages.source("text.plugin.platformToolDefinitions.schedules.2"), false, Map.of("scheduleId", text(100)),
                "scheduleId"));
        result.add(tool("schedule_occurrences", Messages.source("text.plugin.platformToolDefinitions.schedules.3"), false,
            fields(page(), "scheduleId", text(100)), "scheduleId"));
        result.add(
            tool("schedule_employees", Messages.source("text.plugin.platformToolDefinitions.schedules.4"),
                false, page()));
        result.add(
            tool("schedule_preview_times", Messages.source("text.plugin.platformToolDefinitions.schedules.5"),
                false, scheduleRule(), "frequency", "localTime"));
        var definition = fields(scheduleRule(), "name", text(80), "hireId", text(100), "agentVersionId",
            nullableText(100), "inputText", text(20000),
            "enabled", Map.of("type", "boolean"), "maxRetries", integer(0, 2));
        result.add(tool("schedule_create",
            Messages.source("text.plugin.platformToolDefinitions.schedules.6"), true,
            definition, "name", "hireId", "inputText", "frequency", "localTime", "enabled"));
        result.add(
            tool("schedule_update", Messages.source("text.plugin.platformToolDefinitions.schedules.7"), true,
                fields(definition, "scheduleId", text(100), "revision", revision()), "scheduleId", "revision", "name",
                "hireId", "inputText", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_set_enabled", Messages.source("text.plugin.platformToolDefinitions.schedules.8"), true,
            fields(identity("scheduleId"), "enabled", Map.of("type", "boolean")), "scheduleId", "revision", "enabled"));
        result.add(tool("schedule_upgrade_version", Messages.source("text.plugin.platformToolDefinitions.schedules.9"), true,
            identity("scheduleId"), "scheduleId", "revision"));
        result.add(tool("schedule_run_once",
            Messages.source("text.plugin.platformToolDefinitions.schedules.10"), true,
            Map.of("scheduleId", text(100)), "scheduleId"));
        result.add(tool("schedule_delete", Messages.source("text.plugin.platformToolDefinitions.schedules.11"), "destructive",
            identity("scheduleId"), "scheduleId", "revision"));
        // 保留已有智能体工具的参数结构，新操作使用独立入口，已发布插件版本仍可调用。
        result.add(tool("schedule_actions", Messages.source("text.plugin.platformToolDefinitions.schedules.12"), false, Map.of()));
        result.add(tool("schedule_recipients", Messages.source("text.plugin.platformToolDefinitions.schedules.13"), false, page()));
        result.add(tool("schedule_occurrence_get", Messages.source("text.plugin.platformToolDefinitions.schedules.14"), false,
            Map.of("scheduleId", text(100), "occurrenceId", text(100)), "scheduleId", "occurrenceId"));
        var action = Map.of("type", "object", "additionalProperties", false, "required", List.of("type", "schemaVersion", "config"),
            "properties", Map.of("type", text(64), "schemaVersion", integer(1, 1000), "config", Map.of("type", "object")));
        var general = fields(scheduleRule(), "name", text(80), "action", action, "enabled", Map.of("type", "boolean"), "maxRetries", integer(0, 2));
        result.add(tool("schedule_action_create", Messages.source("text.plugin.platformToolDefinitions.schedules.15"),
            true, general, "name", "action", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_action_update", Messages.source("text.plugin.platformToolDefinitions.schedules.16"),
            true, fields(general, "scheduleId", text(100), "revision", revision()), "scheduleId", "revision", "name", "action", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_cancel_occurrence", Messages.source("text.plugin.platformToolDefinitions.schedules.17"),
            true, Map.of("scheduleId", text(100), "occurrenceId", text(100)), "scheduleId", "occurrenceId"));
        return List.copyOf(result);
    }

    private Map<String, Object> todoFields() {
        return fields(Map.of(), "title", text(200), "description", text(5000), "ownerUserId", text(100), "teamId",
            nullableText(100), "dueDate", nullableText(10), "priority", choice("normal", "high"));
    }

    private Map<String, Object> scheduleRule() {
        return fields(Map.of(), "frequency", choice("once", "daily", "weekly", "monthly"), "localDate",
            nullableText(10), "localTime", text(5),
            "weekdays", Map.of("type", "array", "maxItems", 7, "uniqueItems", true, "items", integer(1, 7)),
            "monthDay", Map.of("type", List.of("integer", "null"), "minimum", 1, "maximum", 31), "timezone", text(64));
    }

    private Map<String, Object> page() {
        return fields(Map.of(), "query", text(100), "cursor", text(2048), "limit", integer(1, 20));
    }

    private Map<String, Object> identity(String key) {
        return Map.of(key, text(100), "revision", revision());
    }

    private Map<String, Object> revision() {
        return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$");
    }

    private Map<String, Object> text(int max) {
        return Map.of("type", "string", "maxLength", max);
    }

    private Map<String, Object> nullableText(int max) {
        return Map.of("type", List.of("string", "null"), "maxLength", max);
    }

    private Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private Map<String, Object> fields(Map<String, Object> source, Object... pairs) {
        var result = new LinkedHashMap<>(source);
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private ToolDefinition tool(String name, String description, boolean write, Map<String, Object> properties,
                                String... required) {
        return tool(name, description, write ? "write" : "read", properties, required);
    }

    private ToolDefinition tool(String name, String description, String operation, Map<String, Object> properties,
                                String... required) {
        JsonNode input = json.tree(
            Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties",
                false));
        JsonNode output = json.tree(Map.of("type", "object"));
        boolean write = !operation.equals("read");
        // 删除仍属于写操作，沿用原调用结构摘要，使已发布的固定版本继续可用。
        String hash = json.hash(json.tree(
            Map.of("name", name, "inputSchema", input, "outputSchema", output, "operationClass",
                write ? "write" : "read")));
        return new ToolDefinition(name, description, hash, input, output, json.tree(Map.of("readOnlyHint", !write)),
            operation, write, false, false, List.of(), 30);
    }
}
