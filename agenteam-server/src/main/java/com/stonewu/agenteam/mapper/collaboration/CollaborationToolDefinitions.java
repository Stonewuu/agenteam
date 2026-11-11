package com.stonewu.agenteam.mapper.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 固定工具名与严格参数结构；企业、用户、任务和工作身份不由模型传入。 */
@Component
public class CollaborationToolDefinitions {
    public static final Set<String> WRITES = Set.of("collab_publish_artifact", "collab_assign", "collab_handoff", "collab_wait",
        "collab_ask_user", "collab_submit_result", "collab_complete_task");
    public static final Set<String> READS = Set.of("collab_list_roles", "collab_read_context", "collab_read_artifact");
    private final ResourceJson json;
    private final List<ToolDefinition> definitions;
    private final JsonNode bindingConfig;

    public CollaborationToolDefinitions(ResourceJson json) {
        this.json = json;
        this.definitions = List.copyOf(build());
        this.bindingConfig = json.tree(Map.of("pluginType", "builtin", "builtinCode", "group_collaboration"));
    }

    public static boolean handles(ExecutionToolBinding binding) {
        return "builtin".equals(binding.config().path("pluginType").asText())
            && "group_collaboration".equals(binding.config().path("builtinCode").asText());
    }

    /** 协作工作在自己的岗位版本下使用内置工具，旧插件已经提供的同名工具沿用原调用编号。 */
    public void addTo(RunRecord run, Map<String, ExecutionToolBinding> bindings) {
        if (!"interactive".equals(run.mode()) || !"collaboration".equals(run.executionConfig().path("sourceType").asText())) {
            return;
        }
        var installed = bindings.values().stream().filter(CollaborationToolDefinitions::handles)
            .map(binding -> binding.definition().name()).collect(Collectors.toSet());
        for (var definition : tools()) {
            if (!installed.contains(definition.name())) {
                var binding = new ExecutionToolBinding(run.executionConfig().path("agentId").asText(), run.agentVersionId(),
                    "agent", Messages.source("text.collaboration.collaborationToolDefinitions.addTo.1"), null, definition, bindingConfig);
                bindings.put(binding.alias(), binding);
            }
        }
    }

    public List<ToolDefinition> tools() {
        return definitions;
    }

    private List<ToolDefinition> build() {
        var result = new ArrayList<ToolDefinition>();
        result.add(tool("collab_list_roles", Messages.source("text.collaboration.collaborationToolDefinitions.build.1"), false,
            fields("cursor", text(30), "limit", integer(1, 10))));
        result.add(tool("collab_read_context", Messages.source("text.collaboration.collaborationToolDefinitions.build.2"), false,
            fields("section", choice("overview", "messages", "artifacts", "work"), "cursor", text(30), "limit", integer(1, 50)), "section"));
        result.add(tool("collab_read_artifact", Messages.source("text.collaboration.collaborationToolDefinitions.build.3"), false,
            fields("artifactId", text(100), "version", integer(1, Integer.MAX_VALUE), "offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 16000)),
            "artifactId", "version"));
        var publish = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "artifactKey", text(100), "title", text(160),
            "summary", text(2000), "kind", choice("text", "json", "file"), "schemaName", text(80), "content", Map.of(), "fileId", text(100));
        result.add(tool("collab_publish_artifact", Messages.source("text.collaboration.collaborationToolDefinitions.build.4"), true, publish,
            "expectedInputRevision", "artifactKey", "title", "summary", "kind"));
        var assign = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "targetRoleId", text(100), "goal", text(4000),
            "acceptanceCriteria", text(2000), "artifactRefs", references(), "dependsOnWorkIds", identifiers(), "required", Map.of("type", "boolean"), "replacesWorkItemId", text(100));
        result.add(tool("collab_assign", Messages.source("text.collaboration.collaborationToolDefinitions.build.5"), true, assign,
            "expectedInputRevision", "targetRoleId", "goal", "acceptanceCriteria", "artifactRefs", "dependsOnWorkIds"));
        var handoff = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "targetRoleId", text(100), "completedSummary", text(2000),
            "nextGoal", text(4000), "acceptanceCriteria", text(2000), "artifactRefs", references(), "dependsOnWorkIds", identifiers());
        result.add(tool("collab_handoff", Messages.source("text.collaboration.collaborationToolDefinitions.build.6"), true, handoff,
            "expectedInputRevision", "targetRoleId", "completedSummary", "nextGoal", "acceptanceCriteria", "artifactRefs", "dependsOnWorkIds"));
        result.add(tool("collab_wait", Messages.source("text.collaboration.collaborationToolDefinitions.build.7"), true,
            fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "workItemIds", identifiers(), "reason", text(2000)),
            "expectedInputRevision", "workItemIds", "reason"));
        result.add(tool("collab_ask_user", Messages.source("text.collaboration.collaborationToolDefinitions.build.8"), true,
            fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "question", text(2000), "options", array(text(200), 5)),
            "expectedInputRevision", "question"));
        var finish = fields("expectedInputRevision", integer(1, Integer.MAX_VALUE), "summary", text(2000), "artifactRefs", references());
        var prior = object(fields("workItemId", text(100), "artifactId", text(100), "version", integer(1, Integer.MAX_VALUE), "reason", text(2000)),
            "workItemId", "artifactId", "version", "reason");
        result.add(tool("collab_submit_result", Messages.source("text.collaboration.collaborationToolDefinitions.build.9"), true, finish,
            "expectedInputRevision", "summary", "artifactRefs"));
        finish.put("acceptedPriorResults", array(prior, 20));
        result.add(tool("collab_complete_task", Messages.source("text.collaboration.collaborationToolDefinitions.build.10"), true, finish,
            "expectedInputRevision", "summary", "artifactRefs", "acceptedPriorResults"));
        return List.copyOf(result);
    }

    private ToolDefinition tool(String name, String description, boolean write, Map<String, Object> properties, String... required) {
        JsonNode input = json.tree(object(properties, required));
        JsonNode output = json.tree(Map.of("type", "object"));
        String operation = write ? "write" : "read";
        String hash = json.hash(json.tree(Map.of("name", name, "inputSchema", input, "outputSchema", output, "operationClass", operation)));
        return new ToolDefinition(name, description, hash, input, output, json.tree(Map.of("readOnlyHint", !write)), operation,
            write, false, false, List.of(), 30);
    }

    private Map<String, Object> references() {
        return array(object(fields("artifactId", text(100), "version", integer(1, Integer.MAX_VALUE)), "artifactId", "version"), 20);
    }

    private Map<String, Object> identifiers() {
        return array(text(100), 20);
    }

    private Map<String, Object> array(Map<String, Object> item, int limit) {
        return Map.of("type", "array", "items", item, "maxItems", limit);
    }

    private Map<String, Object> object(Map<String, Object> fields, String... required) {
        return Map.of("type", "object", "properties", fields, "required", List.of(required), "additionalProperties", false);
    }

    private Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private LinkedHashMap<String, Object> fields(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }
}
