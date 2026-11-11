package com.stonewu.agenteam.mapper.workflow;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工作流模板与批量编辑只转换数据，资源权限与保存仍由原服务负责。 */
@Component
public class WorkflowDraftMapper {
    private final ResourceJson json;

    public WorkflowDraftMapper(ResourceJson json) {
        this.json = json;
    }

    public JsonNode initial() {
        return json.tree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(
            node("start", Messages.source("text.workflow.workflowDraftMapper.initial.1"), Map.of("inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string", "title", Messages.source("text.workflow.workflowDraftMapper.initial.2")))))),
            node("end", Messages.source("text.workflow.workflowDraftMapper.initial.3"), Map.of("outputMapping", Map.of("text", "${input.text}")))),
            "edges", List.of(Map.of("edgeId", "start_end", "source", "start", "target", "end", "branch", "default"))));
    }

    public JsonNode schema() {
        var templates = new ArrayList<Object>();
        initial().path("nodes").forEach(templates::add);
        templates.add(node("agent", Messages.source("label.resource.agent"), Map.of("agentVersionId", Messages.source("text.workflow.workflowDraftMapper.schema.1"), "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("skill", Messages.source("label.resource.skill"), Map.of("agentVersionId", Messages.source("text.workflow.workflowDraftMapper.schema.1"), "skillVersionId", Messages.source("text.workflow.workflowDraftMapper.schema.2"), "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("tool", Messages.source("text.workflow.workflowDraftMapper.schema.3"), Map.of("pluginVersionId", Messages.source("text.workflow.workflowDraftMapper.schema.1"), "toolId", Messages.source("text.workflow.workflowDraftMapper.schema.4"), "inputMapping", Map.of())));
        templates.add(node("condition", Messages.source("text.workflow.workflowDraftMapper.schema.5"), Map.of("condition", Map.of("field", "input.text", "operator", "exists"))));
        templates.add(node("transform", Messages.source("text.workflow.workflowDraftMapper.schema.6"), Map.of("fields", List.of(Map.of("target", "text", "source", "input.text")))));
        templates.add(node("approval", Messages.source("text.workflow.workflowDraftMapper.schema.7"), Map.of("title", Messages.source("text.workflow.workflowDraftMapper.schema.8"), "description", "", "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("parallel", Messages.source("text.workflow.workflowDraftMapper.schema.9"), Map.of("joinNodeId", "join")));
        templates.add(node("join", Messages.source("text.workflow.workflowDraftMapper.schema.10"), Map.of("parallelNodeId", "parallel")));
        ObjectNode body = initial().deepCopy();
        ((ObjectNode) body.path("nodes").get(0).path("config")).set("inputSchema", json.tree(Map.of("type", "object")));
        ((ObjectNode) body.path("nodes").get(1).path("config")).set("outputMapping", json.tree(Map.of("result", "${input.item}")));
        templates.add(node("loop", Messages.source("text.workflow.workflowDraftMapper.schema.11"), Map.of("mode", "count", "count", 3, "maxIterations", 20, "items", "${input.items}", "body", body)));
        return json.tree(Map.of("initial", initial(), "nodeTemplates", templates,
            "rules", List.of(Messages.source("text.workflow.workflowDraftMapper.schema.12"),
                Messages.source("text.workflow.workflowDraftMapper.schema.13"),
                Messages.source("text.workflow.workflowDraftMapper.schema.14"),
                Messages.source("text.workflow.workflowDraftMapper.schema.15"),
                Messages.source("text.workflow.workflowDraftMapper.schema.16"),
                Messages.source("text.workflow.workflowDraftMapper.schema.17"),
                Messages.source("text.workflow.workflowDraftMapper.schema.18"))));
    }

    private Map<String, Object> node(String type, String name, Map<String, Object> config) {
        return Map.of("nodeId", type, "type", type, "name", name, "position", Map.of("x", type.equals("end") ? 325 : 40, "y", 80),
            "timeoutSeconds", 120, "failurePolicy", "stop", "config", config);
    }

    public JsonNode patch(JsonNode original, JsonNode operations) {
        if (!operations.isArray() || operations.isEmpty() || operations.size() > 100) {
            throw ApiException.invalidField("operations", Messages.text("error.workflow.workflowDraftMapper.patch.1"));
        }
        ObjectNode result = original.deepCopy();
        for (var operation : operations) {
            ObjectNode graph = result;
            for (var id : operation.path("graphPath")) {
                JsonNode match = null;
                for (var node : graph.path("nodes")) {
                    if (node.path("nodeId").asText().equals(id.asText()) && node.path("type").asText().equals("loop")) {
                        match = node.path("config").path("body");
                    }
                }
                if (!(match instanceof ObjectNode body)) {
                    throw ApiException.invalidField("graphPath", Messages.text("error.workflow.workflowDraftMapper.patch.2"));
                }
                graph = body;
            }
            String action = operation.path("action").asText();
            if (!Set.of("put_node", "remove_node", "put_edge", "remove_edge").contains(action)) {
                throw ApiException.invalidField("action", Messages.text("error.workflow.workflowDraftMapper.patch.3"));
            }
            boolean node = action.endsWith("node"), remove = action.startsWith("remove");
            String field = node ? "nodes" : "edges", key = node ? "nodeId" : "edgeId";
            JsonNode value = operation.path(node ? "node" : "edge");
            String id = remove ? operation.path("id").asText() : value.path(key).asText();
            if (id.isBlank() || !remove && !value.isObject()) {
                throw ApiException.invalidField("operations", Messages.text("error.workflow.workflowDraftMapper.patch.4"));
            }
            var entries = new LinkedHashMap<String, JsonNode>();
            graph.path(field).forEach(item -> entries.put(item.path(key).asText(), item));
            if (remove) {
                if (entries.remove(id) == null) {
                    throw ApiException.invalidField("id", Messages.text("error.workflow.workflowDraftMapper.patch.5"));
                }
            } else {
                entries.put(id, value.deepCopy());
            }
            graph.set(field, json.tree(entries.values()));
            if (node && remove) {
                var edges = new ArrayList<JsonNode>();
                for (var edge : graph.path("edges")) {
                    if (!edge.path("source").asText().equals(id) && !edge.path("target").asText().equals(id)) {
                        edges.add(edge);
                    }
                }
                graph.set("edges", json.tree(edges));
                if (graph.has("groups")) {
                    var groups = new ArrayList<JsonNode>();
                    for (var group : graph.path("groups")) {
                        var members = new ArrayList<String>();
                        group.path("nodeIds").forEach(member -> {
                            if (!member.asText().equals(id)) {
                                members.add(member.asText());
                            }
                        });
                        if (members.size() >= 2) {
                            ObjectNode retained = group.deepCopy();
                            retained.set("nodeIds", json.tree(members));
                            groups.add(retained);
                        }
                    }
                    graph.set("groups", json.tree(groups));
                }
            }
        }
        return result;
    }
}
