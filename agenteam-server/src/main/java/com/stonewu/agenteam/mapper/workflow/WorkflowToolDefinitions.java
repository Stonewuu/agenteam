package com.stonewu.agenteam.mapper.workflow;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作流查询和修改分别声明，写操作使用平台统一确认策略。 */
@Component
public class WorkflowToolDefinitions {
    private final ResourceJson json;

    public WorkflowToolDefinitions(ResourceJson json) {
        this.json = json;
    }

    public List<ToolDefinition> tools() {
        var identity = Map.<String, Object>of("workflowId", text(100));
        var revision = Map.<String, Object>of("type", "string", "pattern", "^[1-9][0-9]{0,18}$");
        return List.of(
            tool("workflow_schema", Messages.source("text.workflow.workflowToolDefinitions.tools.1"), false, Map.of()),
            tool("workflow_list", Messages.source("text.workflow.workflowToolDefinitions.tools.2"), false,
                Map.of("query", text(100), "cursor", text(2048), "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20))),
            tool("workflow_get", Messages.source("text.workflow.workflowToolDefinitions.tools.3"), false, identity, "workflowId"),
            tool("workflow_capabilities", Messages.source("text.workflow.workflowToolDefinitions.tools.4"), false,
                Map.of("kind", Map.of("type", "string", "enum", List.of("agent", "plugin")), "query", text(100), "cursor", text(2048)), "kind"),
            tool("workflow_dependency", Messages.source("text.workflow.workflowToolDefinitions.tools.5"), false,
                Map.of("versionId", text(100)), "versionId"),
            tool("workflow_create", Messages.source("text.workflow.workflowToolDefinitions.tools.6"), true,
                Map.of("name", text(80), "description", text(500), "config", Map.of("type", "object")), "name"),
            tool("workflow_save", Messages.source("text.workflow.workflowToolDefinitions.tools.7"), true,
                fields(identity, "revision", revision, "config", Map.of("type", "object"), "name", text(80), "description", text(500)),
                "workflowId", "revision", "config"),
            tool("workflow_patch", Messages.source("text.workflow.workflowToolDefinitions.tools.8"), true,
                fields(identity, "revision", revision, "operations", Map.of("type", "array", "minItems", 1, "maxItems", 100,
                    "items", Map.of("type", "object", "additionalProperties", false, "required", List.of("action"), "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("put_node", "remove_node", "put_edge", "remove_edge")),
                        "graphPath", Map.of("type", "array", "maxItems", 3, "items", text(64)),
                        "node", Map.of("type", "object"), "edge", Map.of("type", "object"), "id", text(64))))),
                "workflowId", "revision", "operations"),
            tool("workflow_validate", Messages.source("text.workflow.workflowToolDefinitions.tools.9"), false,
                fields(identity, "config", Map.of("type", "object")), "workflowId"));
    }

    private Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private Map<String, Object> fields(Map<String, Object> base, Object... fields) {
        var result = new LinkedHashMap<>(base);
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    private ToolDefinition tool(String name, String description, boolean write, Map<String, Object> properties, String... required) {
        var input = json.tree(Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", List.of(required)));
        var output = json.tree(Map.of("type", "object"));
        String operation = write ? "write" : "read";
        String hash = json.hash(json.tree(Map.of("name", name, "inputSchema", input, "outputSchema", output, "operationClass", operation)));
        return new ToolDefinition(name, description, hash, input, output, json.tree(Map.of("readOnlyHint", !write)), operation, write, false, false, List.of(), 30);
    }
}
