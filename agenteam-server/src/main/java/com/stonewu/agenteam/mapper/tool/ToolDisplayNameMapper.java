package com.stonewu.agenteam.mapper.tool;

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
        Map.of("platform_context", "查看工作空间信息", "platform_time", "查询当前时间", "platform_search",
            "搜索工作空间"),
        "todo_management", Map.of("todo_list", "查询待办", "todo_get", "查看待办详情", "todo_history", "查看待办历史",
            "todo_assignees", "查找待办负责人", "todo_teams", "查询待办团队", "todo_create", "创建待办", "todo_update",
            "修改待办",
            "todo_set_status", "更新待办状态", "todo_transfer", "转交待办", "todo_delete", "删除待办"),
        "schedule_management",
        Map.ofEntries(Map.entry("schedule_list", "查询计划"), Map.entry("schedule_get", "查看计划详情"),
            Map.entry("schedule_occurrences", "查看执行记录"), Map.entry("schedule_employees", "查询执行员工"),
            Map.entry("schedule_preview_times", "预览执行时间"), Map.entry("schedule_create", "创建计划"),
            Map.entry("schedule_update", "修改计划"),
            Map.entry("schedule_actions", "查询定时操作"), Map.entry("schedule_recipients", "查询通知接收人"),
            Map.entry("schedule_occurrence_get", "查看本次执行详情"), Map.entry("schedule_action_create", "创建定时操作"),
            Map.entry("schedule_action_update", "修改定时操作"), Map.entry("schedule_cancel_occurrence", "停止本次执行"),
            Map.entry("schedule_set_enabled", "启用或暂停计划"),
            Map.entry("schedule_upgrade_version", "更新计划员工版本"),
            Map.entry("schedule_run_once", "立即执行计划"), Map.entry("schedule_delete", "删除计划")),
        "web_read", Map.of("read_url", "读取网页"));

    private ToolDisplayNameMapper() {
    }

    public static String label(ExecutionToolBinding binding) {
        return binding.resourceName() + " · " + name(binding.definition(), binding.resourceKind(), binding.config());
    }

    public static String name(ToolDefinition tool, String resourceKind, JsonNode config) {
        String known = switch (resourceKind) {
            case "knowledge" -> tool.name().equals("knowledge_search") ? "检索资料" : null;
            case "data" -> switch (tool.name()) {
                case "data_collections" -> "查看数据集合";
                case "data_query" -> "查询数据";
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
