package com.stonewu.agenteam.model.workflow.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已检查的节点连接；执行只按连接和分支状态推进，坐标不参与排序。
 */
public record WorkflowGraph(Map<String, Node> nodes, List<Edge> edges, List<String> order,
                            Map<String, List<Edge>> incoming, Map<String, List<Edge>> outgoing,
                            Map<String, Set<String>> ancestors,
                            String startId, String endId) {
    public record Node(String id, String type, String name, int timeoutSeconds, String failurePolicy, JsonNode config) {
    }

    public record Edge(String id, String source, String target, String branch) {
    }
}
