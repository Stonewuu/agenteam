package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 基础文件工具归属当前执行资源，目录范围由运行时校验，不从工具参数接受主机目录。
 */
@Component
public class WorkspaceToolDefinitions {
    public static final Set<String> READ_TOOLS = Set.of("read_file", "grep_files", "list_files", "view_image");
    public static final String GUIDANCE = Messages.source("text.workspace.workspaceToolDefinitions.GUIDANCE.1");
    private final ResourceJson json;
    private final List<ToolDefinition> reads;
    private final List<ToolDefinition> writes;
    private final WorkspaceSettings settings;

    public WorkspaceToolDefinitions(ResourceJson json, WorkspaceSettings settings) {
        this.json = json;
        this.settings = settings;
        reads = List.of(
            definition("view_image",
                Messages.source("text.workspace.workspaceToolDefinitions.init.1"),
                Map.of("path", text(512)), List.of("path")),
            definition("read_file",
                Messages.source("text.workspace.workspaceToolDefinitions.init.2"),
                Map.of("path", text(512), "start_line", integer(1, Integer.MAX_VALUE), "end_line",
                    integer(1, Integer.MAX_VALUE), "cursor", text(2048)), List.of("path")),
            definition("grep_files",
                Messages.source("text.workspace.workspaceToolDefinitions.init.3"),
                Map.of("path", text(512), "pattern", text(512), "mode",
                    Map.of("type", "string", "enum", List.of("literal", "regex")),
                    "ignore_case", Map.of("type", "boolean"), "glob", text(128), "before", integer(0, 20), "after",
                    integer(0, 20),
                    "max_matches", integer(1, 100), "cursor", text(2048)), List.of("pattern")),
            definition("list_files",
                Messages.source("text.workspace.workspaceToolDefinitions.init.4"),
                Map.of("path", text(512), "glob", text(128), "limit", integer(1, 50), "cursor", text(2048)),
                List.of()));
        writes = List.of(
            definition("write_file",
                Messages.source("text.workspace.workspaceToolDefinitions.init.5"),
                Map.of("path", text(512), "content", Map.of("type", "string", "maxLength", 131072)),
                List.of("path", "content"), "write", settings.timeoutSeconds()),
            definition("edit_file",
                Messages.source("text.workspace.workspaceToolDefinitions.init.6"),
                Map.of("path", text(512), "old_text", text(131072), "new_text",
                    Map.of("type", "string", "maxLength", 131072), "replace_all", Map.of("type", "boolean")),
                List.of("path", "old_text", "new_text"), "write", settings.timeoutSeconds()),
            definition("execute",
                Messages.source("text.workspace.workspaceToolDefinitions.init.7"),
                Map.of("command", text(65536), "working_directory", text(512), "timeout_seconds",
                    integer(1, settings.timeoutSeconds()), "result_paths",
                    Map.of("type", "array", "items", text(512), "maxItems", 20, "uniqueItems", true)),
                List.of("command"), settings.network().equals("none") ? "write" : "unknown", settings.timeoutSeconds()),
            definition("export_file",
                Messages.source("text.workspace.workspaceToolDefinitions.init.8"),
                Map.of("path", text(512)), List.of("path"), "write", settings.timeoutSeconds()));
    }

    public Map<String, ExecutionToolBinding> list(RunRecord run) {
        String kind = run.executionConfig().has("workflowId") ? "workflow" : "agent";
        String resource = run.executionConfig().path(kind + "Id").asText();
        if (resource.isBlank()) {
            throw new IllegalStateException(Messages.text("error.workspace.workspaceToolDefinitions.list.1"));
        }
        Map<String, ExecutionToolBinding> result = new LinkedHashMap<>();
        var definitions = new ArrayList<>(reads);
        definitions.addAll(writes);
        for (var tool : definitions) {
            if (tool.name().equals("execute") && !settings.executeEnabled()) {
                continue;
            }
            var config = json.tree(Map.of("workspaceTool", true, "timeoutSeconds", tool.timeoutSeconds()));
            var binding = new ExecutionToolBinding(resource, run.agentVersionId(), kind, Messages.source("text.tool.toolApprovalSummaryMapper.map.1"), null, tool, config);
            result.put(binding.alias(), binding);
        }
        return Map.copyOf(result);
    }

    public static boolean handles(ExecutionToolBinding binding) {
        return Set.of("agent", "workflow").contains(binding.resourceKind()) && binding.config().path("workspaceTool")
            .asBoolean(false);
    }

    /**
     * 只供保存已完成的委派结果使用，不登记到模型可调用工具中。
     */
    public Map<String, ExecutionToolBinding> delegatedResults(RunRecord run) {
        Map<String, ExecutionToolBinding> result = new LinkedHashMap<>();
        String kind = run.executionConfig().has("workflowId") ? "workflow" : "agent";
        String id = run.executionConfig().path(kind + "Id").asText();
        for (String name : List.of("agent_spawn", "agent_send")) {
            result.put(name, resultBinding(id, run.agentVersionId(), kind, Messages.source("text.workspace.workspaceToolDefinitions.delegatedResults.1"), "read_" + name + "_result"));
        }
        for (var dependency : run.executionConfig().path("dependencies")) {
            if (dependency.path("kind").asText().equals("workflow")) {
                String version = dependency.path("versionId").asText();
                String name = "workflow_" + UUID.nameUUIDFromBytes(version.getBytes(StandardCharsets.UTF_8)).toString()
                    .replace("-", "");
                result.put(name,
                    resultBinding(dependency.path("resourceId").asText(), version, "workflow", Messages.source("text.workspace.workspaceToolDefinitions.delegatedResults.2"),
                        "read_workflow_result"));
            }
        }
        return Map.copyOf(result);
    }

    private ExecutionToolBinding resultBinding(String id, String version, String kind, String title, String name) {
        var tool = definition(name, Messages.source("text.workspace.workspaceToolDefinitions.resultBinding.1"),
            Map.of("sourceCallId", text(128), "sourceArgumentsHash", text(64)),
            List.of("sourceCallId", "sourceArgumentsHash"));
        return new ExecutionToolBinding(id, version, kind, title, null, tool,
            json.tree(Map.of("delegatedResult", true)));
    }

    private ToolDefinition definition(String name, String description, Map<String, Object> properties,
                                      List<String> required) {
        return definition(name, description, properties, required, "read", settings.timeoutSeconds());
    }

    private ToolDefinition definition(String name, String description, Map<String, Object> properties,
                                      List<String> required, String operation, int timeout) {
        JsonNode schema = json.tree(
            Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false));
        String title = switch (name) {
            case "read_file" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.1");
            case "view_image" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.2");
            case "grep_files" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.3");
            case "list_files" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.4");
            case "write_file" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.5");
            case "edit_file" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.6");
            case "execute" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.7");
            case "export_file" -> Messages.source("text.workspace.workspaceToolDefinitions.definition.8");
            case "read_agent_spawn_result", "read_agent_send_result" -> Messages.source("text.workspace.workspaceToolDefinitions.delegatedResults.1");
            case "read_workflow_result" -> Messages.source("text.workspace.workspaceToolDefinitions.delegatedResults.2");
            default -> name;
        };
        return new ToolDefinition(name, description, json.hash(schema), schema, null,
            json.tree(Map.of("readOnlyHint", operation.equals("read"), "title", title)),
            operation, false, false, true, List.of(), timeout);
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }
}
