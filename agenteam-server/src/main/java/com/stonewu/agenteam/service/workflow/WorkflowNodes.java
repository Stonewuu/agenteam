package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.http.ApiException;

import java.util.ArrayList;
import java.util.List;

/** 所有依赖选择与复制都使用同一遍历，包含循环内部的节点。 */
public final class WorkflowNodes {
    public record Entry(JsonNode node, String path) {
    }

    private WorkflowNodes() {
    }

    public static List<Entry> entries(JsonNode config) {
        var result = new ArrayList<Entry>();
        collect(config, "", 0, result);
        return List.copyOf(result);
    }

    public static List<JsonNode> all(JsonNode config) {
        return entries(config).stream().map(Entry::node).toList();
    }

    public static void clearDependency(ObjectNode config, String binding) {
        for (var entry : entries(config)) {
            String prefix = entry.path() + "/config/";
            if (binding.startsWith(prefix) && !binding.substring(prefix.length()).contains("/")) {
                ((ObjectNode) entry.node().path("config")).putNull(binding.substring(prefix.length()));
                return;
            }
        }
    }

    private static void collect(JsonNode graph, String prefix, int depth, List<Entry> result) {
        if (depth > 3) {
            throw ApiException.invalidField("config.nodes", "循环最多嵌套三层。");
        }
        for (var node : graph.path("nodes")) {
            String path = prefix + "nodes/" + node.path("nodeId").asText();
            result.add(new Entry(node, path));
            if (result.size() > 50) {
                throw ApiException.invalidField("config.nodes", "包含循环内部步骤在内，最多允许 50 个节点。");
            }
            if (node.path("type").asText().equals("loop")) {
                collect(node.path("config").path("body"), path + "/config/body/", depth + 1, result);
            }
        }
    }
}
