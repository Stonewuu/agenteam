package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.plugin.PlatformToolInputMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoTransferRequest;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.service.schedule.ScheduleTimePreviewService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.service.schedule.ScheduleSelectionService;
import com.stonewu.agenteam.service.schedule.ScheduleOccurrenceCancellation;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import com.stonewu.agenteam.service.todo.TodoSelectionService;
import com.stonewu.agenteam.service.workspace.WorkspaceSearchService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 业务规则沿用原有服务，不构造浏览器请求，不借用资源维护者权限。
 */
@Service
public class PlatformBusinessTools {
    private final TodoManagementService todos;
    private final TodoQueryService todoQueries;
    private final TodoSelectionService selections;
    private final ScheduleManagementService schedules;
    private final ScheduleQueryService scheduleQueries;
    private final ScheduleTimePreviewService times;
    private final ScheduleTriggerService triggers;
    private final EmployeeQueryService employees;
    private final WorkspaceSearchService search;
    private final EnterpriseMapper enterprises;
    private final PlatformToolInputMapper input;
    private final ResourceJson json;
    private final Clock clock;
    private final ScheduleSelectionService scheduleSelections;
    private final ScheduleOccurrenceCancellation cancellations;

    public PlatformBusinessTools(TodoManagementService todos, TodoQueryService todoQueries,
                                 TodoSelectionService selections,
                                 ScheduleManagementService schedules, ScheduleQueryService scheduleQueries,
                                 ScheduleTimePreviewService times, ScheduleTriggerService triggers,
                                 EmployeeQueryService employees, WorkspaceSearchService search,
                                 EnterpriseMapper enterprises, PlatformToolInputMapper input, ResourceJson json,
                                 Clock clock, ScheduleSelectionService scheduleSelections, ScheduleOccurrenceCancellation cancellations) {
        this.todos = todos;
        this.todoQueries = todoQueries;
        this.selections = selections;
        this.schedules = schedules;
        this.scheduleQueries = scheduleQueries;
        this.times = times;
        this.triggers = triggers;
        this.employees = employees;
        this.search = search;
        this.enterprises = enterprises;
        this.input = input;
        this.json = json;
        this.clock = clock;
        this.scheduleSelections = scheduleSelections;
        this.cancellations = cancellations;
    }

    public JsonNode call(BuiltinExecutionContext context, String name, JsonNode args) {
        var actor = context.actor();
        String query = input.text(args, "query"), cursor = input.text(args, "cursor");
        int limit = input.limit(args);
        Object value = switch (name) {
            case "platform_context" ->
                Map.of("enterprise", enterprises.findById(actor.enterpriseId()).orElseThrow().name(),
                    "user", actor.user().displayName(), "timezone", enterprises.timezone(actor.enterpriseId()));
            case "platform_time" -> {
                String timezone = enterprises.timezone(actor.enterpriseId());
                var now = clock.instant().atZone(ZoneId.of(timezone));
                yield Map.of("time", now.toOffsetDateTime().toString(), "date", now.toLocalDate().toString(),
                    "timezone", timezone);
            }
            case "platform_search" -> search.get(actor, query);
            case "todo_list" -> todoQueries.list(actor, input.text(args, "scope"), input.text(args, "status"),
                input.text(args, "teamId"), query, cursor, limit);
            case "todo_get" -> todoQueries.get(actor, input.text(args, "todoId"));
            case "todo_history" -> todoQueries.history(actor, input.text(args, "todoId"), cursor, limit);
            case "todo_assignees" ->
                selections.assignees(actor, input.text(args, "todoId"), input.text(args, "teamId"), query, cursor,
                    limit);
            case "todo_teams" -> selections.teams(actor, input.text(args, "purpose"), query, cursor, limit);
            case "todo_create" ->
                compact(todos.create(actor, input.todo(context, args, null)), "待办已创建。", "id", "revision", "title",
                    "status", "dueDate", "owner", "teamName");
            case "todo_update" -> {
                String id = input.text(args, "todoId");
                var previous = todoQueries.get(actor, id);
                yield compact(todos.update(actor, id, input.todo(context, args, previous), input.revision(args)),
                    "待办已修改。", "id", "revision", "title", "status", "dueDate", "owner", "teamName");
            }
            case "todo_set_status" -> {
                var request = new TodoStatusRequest(input.text(args, "status"), input.text(args, "reason"));
                InputValidation.validate(request);
                yield compact(todos.status(actor, input.text(args, "todoId"), request, input.revision(args)),
                    "待办状态已修改。", "id", "revision", "title", "status");
            }
            case "todo_transfer" -> {
                var request = new TodoTransferRequest(input.text(args, "ownerUserId"), input.text(args, "reason"));
                InputValidation.validate(request);
                yield compact(todos.transfer(actor, input.text(args, "todoId"), request, input.revision(args)),
                    "待办已转交。", "id", "revision", "title", "owner");
            }
            case "todo_delete" -> {
                String id = input.text(args, "todoId");
                todos.delete(actor, id, input.revision(args));
                yield Map.of("id", id, "content", "待办已删除。");
            }
            case "schedule_list" -> scheduleQueries.list(actor, query, cursor, limit);
            case "schedule_actions" -> Map.of("items", scheduleSelections.actions(actor));
            case "schedule_recipients" -> scheduleSelections.recipients(actor, query, cursor, limit);
            case "schedule_occurrence_get" -> scheduleQueries.occurrence(actor, input.text(args, "scheduleId"), input.text(args, "occurrenceId"));
            case "schedule_cancel_occurrence" -> cancellations.cancel(actor, input.text(args, "scheduleId"), input.text(args, "occurrenceId"));
            case "schedule_get" -> scheduleQueries.get(actor, input.text(args, "scheduleId"));
            case "schedule_occurrences" ->
                scheduleQueries.occurrences(actor, input.text(args, "scheduleId"), cursor, limit);
            case "schedule_employees" -> {
                var page = employees.list(actor, "mine", query, List.of(), cursor, limit);
                var result = (ObjectNode) json.tree(page);
                var available = result.putArray("items");
                for (var employee : page.items()) {
                    if (employee.canRun()) {
                        available.add(json.tree(
                            Map.of("agentId", employee.agentId(), "hireId", employee.hireId(), "name", employee.name(),
                                "description", employee.description())));
                    }
                }
                yield result;
            }
            case "schedule_preview_times" ->
                times.preview(actor, input.rule(args, enterprises.timezone(actor.enterpriseId())));
            case "schedule_create", "schedule_action_create" ->
                compact(schedules.create(actor, input.schedule(args, enterprises.timezone(actor.enterpriseId()))),
                    "定时任务已创建。", "id", "revision", "name", "enabled", "frequency", "localDate", "localTime",
                    "timezone", "nextRunAt", "action");
            case "schedule_update", "schedule_action_update" -> compact(schedules.update(actor, input.text(args, "scheduleId"),
                    input.schedule(args, enterprises.timezone(actor.enterpriseId())), input.revision(args)),
                "定时任务已修改。", "id", "revision", "name", "enabled", "frequency", "localDate", "localTime",
                "timezone", "nextRunAt", "action");
            case "schedule_set_enabled" -> compact(
                schedules.enabled(actor, input.text(args, "scheduleId"), args.path("enabled").asBoolean(),
                    input.revision(args)), "定时任务启用状态已修改。", "id", "revision", "name", "enabled", "nextRunAt");
            case "schedule_upgrade_version" ->
                compact(schedules.upgrade(actor, input.text(args, "scheduleId"), input.revision(args)),
                    "计划的员工版本已更新。", "id", "revision", "name", "agentVersionId");
            case "schedule_run_once" -> triggers.manual(actor, input.text(args, "scheduleId"), context.operationId());
            case "schedule_delete" -> {
                String id = input.text(args, "scheduleId");
                schedules.delete(actor, id, input.revision(args));
                yield Map.of("id", id, "content", "定时任务已删除。");
            }
            default -> throw new IllegalArgumentException("平台业务工具没有登记");
        };
        JsonNode result = json.tree(value);
        removeActions(result);
        return result;
    }

    private JsonNode compact(Object value, String summary, String... fields) {
        JsonNode source = json.tree(value);
        ObjectNode result = json.tree(Map.of("content", summary)).deepCopy();
        for (var field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field));
            }
        }
        return result;
    }

    private void removeActions(JsonNode value) {
        if (value instanceof ObjectNode object) {
            object.remove("allowedActions");
        }
        for (var child : value) {
            if (child.isContainerNode()) {
                removeActions(child);
            }
        }
    }
}
