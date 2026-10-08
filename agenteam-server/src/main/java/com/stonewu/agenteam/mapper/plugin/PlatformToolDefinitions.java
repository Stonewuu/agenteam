package com.stonewu.agenteam.mapper.plugin;

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
        return List.of(tool("platform_context", "读取当前企业、本人和企业时区。", false, Map.of()),
            tool("platform_time", "读取当前准确时间、日期及企业时区。安排日期前先读取，不猜测今天的日期。", false,
                Map.of()),
            tool("platform_search", "在当前用户可见的工作空间中搜索，返回实际资源及页面入口。", false,
                Map.of("query", text(100)), "query"));
    }

    public List<ToolDefinition> todos() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("todo_list", "查询本人或有权查看的团队待办。截止日期不代表到点提醒。", false,
            fields(page(), "scope", choice("mine", "team"), "status",
                choice("open", "pending", "in_progress", "completed", "cancelled"), "teamId", nullableText(100))));
        result.add(
            tool("todo_get", "读取一条有权查看的待办及当前修改版本，修改前先读取。", false, Map.of("todoId", text(100)),
                "todoId"));
        result.add(
            tool("todo_history", "查询待办的真实修改历史。", false, fields(page(), "todoId", text(100)), "todoId"));
        result.add(tool("todo_assignees", "查询当前待办可以选择的真实负责人，不要猜测成员编号。", false,
            fields(page(), "todoId", nullableText(100), "teamId", nullableText(100))));
        result.add(tool("todo_teams", "查询当前用户有权筛选或分配待办的团队。", false,
            fields(page(), "purpose", choice("filter", "assign"))));
        result.add(
            tool("todo_create", "创建待办。负责人省略时归本人；截止日期只是日期，不会产生定时提醒。确认后才保存。", true,
                todoFields(), "title"));
        result.add(
            tool("todo_update", "修改待办。先读取当前版本；未提交的内容保留原值，明确提交空值可清除团队和截止日期。", true,
                fields(todoFields(), "todoId", text(100), "revision", revision()), "todoId", "revision"));
        result.add(tool("todo_set_status", "将待办标为待处理、进行中、完成或取消，确认后按当前版本修改。", true,
            fields(identity("todoId"), "status", choice("pending", "in_progress", "completed", "cancelled"), "reason",
                text(500)), "todoId", "revision", "status"));
        result.add(tool("todo_transfer", "转交待办负责人，不转交来源对话的访问权限。", true,
            fields(identity("todoId"), "ownerUserId", text(100), "reason", text(500)), "todoId", "revision",
            "ownerUserId"));
        result.add(tool("todo_delete", "删除指定待办，按当前版本执行。", "destructive", identity("todoId"), "todoId",
            "revision"));
        return List.copyOf(result);
    }

    public List<ToolDefinition> schedules() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("schedule_list", "查询本人定时任务，不能查询其他成员的私有计划。", false, page()));
        result.add(
            tool("schedule_get", "读取本人定时任务及当前修改版本，修改前先读取。", false, Map.of("scheduleId", text(100)),
                "scheduleId"));
        result.add(tool("schedule_occurrences", "查询本人定时任务的实际触发与执行记录。", false,
            fields(page(), "scheduleId", text(100)), "scheduleId"));
        result.add(
            tool("schedule_employees", "查询本人已雇佣且可运行的员工，创建计划时使用返回的 hireId，不会自动雇佣员工。",
                false, page()));
        result.add(
            tool("schedule_preview_times", "预览规则对应的未来五次实际执行时间，不创建计划。省略时区时使用企业时区。",
                false, scheduleRule(), "frequency", "localTime"));
        var definition = fields(scheduleRule(), "name", text(80), "hireId", text(100), "agentVersionId",
            nullableText(100), "inputText", text(20000),
            "enabled", Map.of("type", "boolean"), "maxRetries", integer(0, 2));
        result.add(tool("schedule_create",
            "创建本人定时任务。先选择已雇佣员工并核对未来执行时间；enabled 明确决定是否启用。确认后保存。", true,
            definition, "name", "hireId", "inputText", "frequency", "localTime", "enabled"));
        result.add(
            tool("schedule_update", "修改本人定时任务的完整设置。先读取当前内容和修改版本，修改只影响未来执行。", true,
                fields(definition, "scheduleId", text(100), "revision", revision()), "scheduleId", "revision", "name",
                "hireId", "inputText", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_set_enabled", "启用或暂停本人计划的未来触发，不停止已经开始的执行。", true,
            fields(identity("scheduleId"), "enabled", Map.of("type", "boolean")), "scheduleId", "revision", "enabled"));
        result.add(tool("schedule_upgrade_version", "将本人计划未来使用的员工更新为当前可用的发布版本。", true,
            identity("scheduleId"), "scheduleId", "revision"));
        result.add(tool("schedule_run_once",
            "请求立即执行一次本人已有定时任务；结果以实际排队或阻止状态为准，不表示任务已经完成。", true,
            Map.of("scheduleId", text(100)), "scheduleId"));
        result.add(tool("schedule_delete", "删除本人已暂停且没有活动执行的计划，不删除历史执行记录。", "destructive",
            identity("scheduleId"), "scheduleId", "revision"));
        // 保留已有智能体工具的参数结构，新操作使用独立入口，已发布插件版本仍可调用。
        result.add(tool("schedule_actions", "读取当前可用的定时操作及参数格式，创建通用操作前先读取。", false, Map.of()));
        result.add(tool("schedule_recipients", "查询可以选择的通知接收成员及其已绑定渠道，不要猜测成员或应用编号。", false, page()));
        result.add(tool("schedule_occurrence_get", "读取本人计划某次执行的固定内容及实际接收结果。", false,
            Map.of("scheduleId", text(100), "occurrenceId", text(100)), "scheduleId", "occurrenceId"));
        var action = Map.of("type", "object", "additionalProperties", false, "required", List.of("type", "schemaVersion", "config"),
            "properties", Map.of("type", text(64), "schemaVersion", integer(1, 1000), "config", Map.of("type", "object")));
        var general = fields(scheduleRule(), "name", text(80), "action", action, "enabled", Map.of("type", "boolean"), "maxRetries", integer(0, 2));
        result.add(tool("schedule_action_create", "创建本人定时操作。先读可用操作、真实接收人和未来时间；通知须明确标题、正文与接收人。通知 maxRetries 为零。确认后保存。",
            true, general, "name", "action", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_action_update", "完整修改本人定时操作，仅影响未来触发；先读当前计划、可用操作和接收人，使用当前版本并在确认后保存。",
            true, fields(general, "scheduleId", text(100), "revision", revision()), "scheduleId", "revision", "name", "action", "frequency", "localTime", "enabled"));
        result.add(tool("schedule_cancel_occurrence", "停止本人计划指定的一次执行。已保存的通知和平台已接受的消息保留；正在请求时等待真实结果。确认后操作。",
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
