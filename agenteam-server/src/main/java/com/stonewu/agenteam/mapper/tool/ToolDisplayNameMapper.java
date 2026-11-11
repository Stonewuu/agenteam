package com.stonewu.agenteam.mapper.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;

import java.util.Map;

/**
 * 展示名称独立于调用名称；只按已确认的内置插件身份翻译方法名。
 */
public final class ToolDisplayNameMapper {
    private static final Map<String, Map<String, String>> BUILTINS = Map.of(
        "platform_basics",
        Map.of("platform_context", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.1"), "platform_time", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.2"), "platform_search",
            Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.3")),
        "todo_management", Map.of("todo_list", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.4"), "todo_get", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.5"), "todo_history", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.6"),
            "todo_assignees", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.7"), "todo_teams", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.8"), "todo_create", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.9"), "todo_update",
            Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.10"),
            "todo_set_status", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.11"), "todo_transfer", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.12"), "todo_delete", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.13")),
        "schedule_management",
        Map.ofEntries(Map.entry("schedule_list", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.14")), Map.entry("schedule_get", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.15")),
            Map.entry("schedule_occurrences", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.16")), Map.entry("schedule_employees", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.17")),
            Map.entry("schedule_preview_times", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.18")), Map.entry("schedule_create", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.19")),
            Map.entry("schedule_update", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.20")),
            Map.entry("schedule_actions", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.21")), Map.entry("schedule_recipients", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.22")),
            Map.entry("schedule_occurrence_get", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.23")), Map.entry("schedule_action_create", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.24")),
            Map.entry("schedule_action_update", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.25")), Map.entry("schedule_cancel_occurrence", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.26")),
            Map.entry("schedule_set_enabled", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.27")),
            Map.entry("schedule_upgrade_version", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.28")),
            Map.entry("schedule_run_once", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.29")), Map.entry("schedule_delete", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.30"))),
        "web_read", Map.of("read_url", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.31")),
        "workflow_management", Map.of("workflow_schema", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.32"), "workflow_list", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.33"),
            "workflow_get", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.34"), "workflow_capabilities", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.35"), "workflow_dependency", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.36"),
            "workflow_create", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.37"), "workflow_save", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.38"), "workflow_patch", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.39"),
            "workflow_validate", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.40")),
        "group_collaboration", Map.of("collab_list_roles", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.41"), "collab_read_context", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.42"),
            "collab_read_artifact", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.43"), "collab_publish_artifact", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.44"),
            "collab_assign", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.45"), "collab_handoff", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.46"), "collab_wait", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.47"),
            "collab_ask_user", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.48"), "collab_submit_result", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.49"), "collab_complete_task", Messages.source("text.tool.toolDisplayNameMapper.BUILTINS.50")));

    private ToolDisplayNameMapper() {
    }

    public static String label(ExecutionToolBinding binding) {
        return binding.resourceName() + " · " + name(binding.definition(), binding.resourceKind(), binding.config());
    }

    public static String name(ToolDefinition tool, String resourceKind, JsonNode config) {
        String known = switch (resourceKind) {
            case "knowledge" -> tool.name().equals("knowledge_search") ? Messages.text("text.tool.toolDisplayNameMapper.name.1") : null;
            case "data" -> switch (tool.name()) {
                case "data_collections" -> Messages.text("text.tool.toolDisplayNameMapper.name.2");
                case "data_query" -> Messages.text("text.tool.toolDisplayNameMapper.name.3");
                default -> null;
            };
            default -> config != null && config.path("pluginType").asText().equals("builtin")
                ? BUILTINS.getOrDefault(config.path("builtinCode").asText(), Map.of()).get(tool.name()) : null;
        };
        if (known != null) {
            return known;
        }
        JsonNode title = tool.annotations() == null ? null : tool.annotations().get("title");
        if (title == null || !title.isTextual() || title.asText().isBlank()) {
            return tool.name();
        }
        String value = title.asText().strip().replaceAll("\\s+", " ");
        return value.substring(0, value.offsetByCodePoints(0, Math.min(128, value.codePointCount(0, value.length()))));
    }
}
