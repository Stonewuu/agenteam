package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Edge;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 发布、测试与正式执行共用图校验；只允许明确分支，不按照画布位置执行。
 */
@Component
public class WorkflowGraphValidator {
    private final ToolSchemaValidation inputSchemas;
    private final WorkflowExpressions expressions;
    private final WorkflowConditions conditions;

    public WorkflowGraphValidator(ToolSchemaValidation inputSchemas,
                                  WorkflowExpressions expressions, WorkflowConditions conditions) {
        this.inputSchemas = inputSchemas;
        this.expressions = expressions;
        this.conditions = conditions;
    }

    public WorkflowGraph compile(JsonNode config) {
        WorkflowInputValidation.validate(config, true);
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Integer> indexes = new HashMap<>();
        int index = 0;
        for (var value : config.path("nodes")) {
            String id = value.path("nodeId").asText();
            if (nodes.containsKey(id)) {
                throw invalid(id, null, "nodes[" + index + "].nodeId", Messages.text("error.resource.workflowConfigurationValidator.validateDraftGraph.1"));
            }
            nodes.put(id, new Node(id, value.path("type").asText(), value.path("name").asText(),
                value.path("timeoutSeconds").asInt(),
                value.path("failurePolicy").asText(), value.path("config").deepCopy()));
            indexes.put(id, index++);
        }
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>(), incoming = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
        });
        List<Edge> edges = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>(), connections = new HashSet<>();
        index = 0;
        for (var value : config.path("edges")) {
            var edge = new Edge(value.path("edgeId").asText(), value.path("source").asText(),
                value.path("target").asText(), value.path("branch").asText());
            if (!edgeIds.add(edge.id()) || !nodes.containsKey(edge.source()) || !nodes.containsKey(
                edge.target()) || edge.source().equals(edge.target())
                || !connections.add(edge.source() + ":" + edge.target() + ":" + edge.branch())) {
                throw invalid(null, edge.id(), "edges[" + index + "]",
                    Messages.text("error.workflow.workflowGraphValidator.compile.1"));
            }
            edges.add(edge);
            outgoing.get(edge.source()).add(edge);
            incoming.get(edge.target()).add(edge);
            index++;
        }
        var starts = nodes.values().stream().filter(node -> node.type().equals("start")).toList();
        var ends = nodes.values().stream().filter(node -> node.type().equals("end")).toList();
        if (starts.size() != 1 || ends.size() != 1) {
            throw invalid(null, null, "nodes", Messages.text("error.workflow.workflowGraphValidator.compile.2"));
        }
        String start = starts.getFirst().id(), end = ends.getFirst().id();
        var next = neighbors(outgoing, false);
        var previous = neighbors(incoming, true);
        List<String> ordered = order(nodes.keySet(), previous, next);
        if (reachable(start, next, null).size() != nodes.size() || reachable(end, previous,
            null).size() != nodes.size()) {
            throw invalid(null, null, "nodes", Messages.text("error.workflow.workflowGraphValidator.compile.3"));
        }
        var ancestors = ancestors(ordered, previous);
        var dominators = dominators(ordered, previous);
        var reverse = new ArrayList<>(ordered);
        Collections.reverse(reverse);
        var postdominators = dominators(reverse, next);
        for (var node : nodes.values()) {
            String field = "nodes[" + indexes.get(node.id()) + "]";
            validateConnections(node, outgoing.get(node.id()), incoming.get(node.id()), field);
            if (node.type().equals("parallel")) {
                validateParallel(node, nodes, next, previous, dominators, postdominators, field);
            }
            if (node.type().equals("join")) {
                var parallel = nodes.get(node.config().path("parallelNodeId").asText());
                if (parallel == null || !parallel.type().equals("parallel") || !parallel.config().path("joinNodeId")
                    .asText().equals(node.id())) {
                    throw invalid(node.id(), null, field + ".config.parallelNodeId",
                        Messages.text("error.workflow.workflowGraphValidator.compile.4"));
                }
            }
            validateExpressions(node, ancestors.get(node.id()), field);
            if (node.type().equals("loop")) {
                try {
                    compile(node.config().path("body"));
                } catch (ApiException invalid) {
                    throw invalid(node.id(), null, field + ".config.body", Messages.text("error.workflow.workflowGraphValidator.compile.5", invalid.getReason()));
                }
            }
        }
        return new WorkflowGraph(Collections.unmodifiableMap(nodes), List.copyOf(edges), List.copyOf(ordered),
            freeze(incoming), freeze(outgoing),
            Map.copyOf(ancestors), start, end);
    }

    private void validateConnections(Node node, List<Edge> next, List<Edge> previous, String field) {
        String type = node.type();
        if (type.equals("start") && (!previous.isEmpty() || next.size() != 1)) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.1"));
        }
        if (type.equals("end") && !next.isEmpty()) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.2"));
        }
        var labels = next.stream().map(Edge::branch).toList();
        if (type.equals("condition") && !(labels.size() == 2 && Set.copyOf(labels).equals(Set.of("true", "false")))) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.3"));
        }
        if (type.equals("approval") && !(labels.size() == 2 && Set.copyOf(labels)
            .equals(Set.of("approve", "reject")))) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.4"));
        }
        if (!Set.of("condition", "approval").contains(type) && labels.stream()
            .anyMatch(label -> !label.equals("default"))) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.5"));
        }
        if (!Set.of("start", "end", "condition", "approval", "parallel").contains(type) && next.size() != 1) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateConnections.6"));
        }
        if (node.failurePolicy().equals("continue") && !Set.of("agent", "skill", "tool", "transform").contains(type)) {
            throw invalid(node.id(), null, field + ".failurePolicy",
                Messages.text("error.workflow.workflowGraphValidator.validateConnections.7"));
        }
    }

    private void validateParallel(Node node, Map<String, Node> nodes, Map<String, List<String>> next,
                                  Map<String, List<String>> previous,
                                  Map<String, Set<String>> dominators, Map<String, Set<String>> postdominators,
                                  String field) {
        String join = node.config().path("joinNodeId").asText();
        var target = nodes.get(join);
        if (next.get(node.id()).size() < 2 || next.get(node.id()).size() > 4 || target == null || !target.type()
            .equals("join")
            || !target.config().path("parallelNodeId").asText().equals(node.id())) {
            throw invalid(node.id(), null, field + ".config.joinNodeId",
                Messages.text("error.workflow.workflowGraphValidator.validateParallel.1"));
        }
        if (!dominators.get(join).contains(node.id())) {
            throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateParallel.2"));
        }
        Set<String> seen = new HashSet<>();
        for (String branch : next.get(node.id())) {
            if (!postdominators.get(branch).contains(join)) {
                throw invalid(node.id(), null, field, Messages.text("error.workflow.workflowGraphValidator.validateParallel.3"));
            }
            var members = reachable(branch, next, join);
            for (String member : members) {
                if (!seen.add(member)) {
                    throw invalid(member, null, field, Messages.text("error.workflow.workflowGraphValidator.validateParallel.4"));
                }
                if (previous.get(member).stream()
                    .anyMatch(parent -> !parent.equals(node.id()) && !members.contains(parent))) {
                    throw invalid(member, null, field, Messages.text("error.workflow.workflowGraphValidator.validateParallel.5"));
                }
            }
        }
    }

    private void validateExpressions(Node node, Set<String> previous, String field) {
        JsonNode values = node.config();
        try {
            if (node.type().equals("condition")) {
                conditions.validate(values.path("condition"), previous);
            }
            expressions.validateTemplates(values.get("inputMapping"), previous);
            expressions.validateTemplates(values.get("outputMapping"), previous);
            if (node.type().equals("loop")) {
                expressions.validateTemplates(values.get("items"), previous);
            }
            if (node.type().equals("approval")) {
                expressions.validateTemplates(values.get("title"), previous);
                expressions.validateTemplates(values.get("description"), previous);
            }
            if (node.type().equals("transform")) {
                Set<String> targets = new HashSet<>();
                for (var item : values.path("fields")) {
                    if (!targets.add(item.path("target").asText())) {
                        throw WorkflowExpressions.syntax(Messages.text("error.workflow.workflowGraphValidator.validateExpressions.1"));
                    }
                    if (item.has("source")) {
                        expressions.validateSelector(item.path("source").asText(), previous);
                    }
                    if (item.has("template")) {
                        expressions.validateTemplates(item.get("template"), previous);
                    }
                }
            }
            if (node.type().equals("start")) {
                try {
                    inputSchemas.compile(values.path("inputSchema"));
                } catch (ApiException unsupported) {
                    throw WorkflowExpressions.syntax(Messages.text("error.workflow.workflowGraphValidator.validateExpressions.2"));
                }
            }
        } catch (ApiException invalid) {
            throw invalid(node.id(), null, field + ".config", invalid.getReason());
        }
    }

    private List<String> order(Set<String> nodes, Map<String, List<String>> previous, Map<String, List<String>> next) {
        Map<String, Integer> counts = new HashMap<>();
        var ready = new ArrayDeque<String>();
        nodes.forEach(id -> {
            counts.put(id, previous.get(id).size());
            if (previous.get(id).isEmpty()) {
                ready.add(id);
            }
        });
        List<String> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            result.add(id);
            for (String child : next.get(id)) {
                if (counts.compute(child, (key, count) -> count - 1) == 0) {
                    ready.add(child);
                }
            }
        }
        if (result.size() != nodes.size()) {
            throw invalid(null, null, "edges", Messages.text("error.workflow.workflowGraphValidator.order.1"));
        }
        return result;
    }

    private Set<String> reachable(String start, Map<String, List<String>> edges, String stop) {
        Set<String> seen = new HashSet<>();
        var pending = new ArrayDeque<String>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!id.equals(stop) && seen.add(id)) {
                pending.addAll(edges.get(id));
            }
        }
        return seen;
    }

    private Map<String, Set<String>> ancestors(List<String> order, Map<String, List<String>> previous) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String id : order) {
            Set<String> values = new HashSet<>();
            for (String parent : previous.get(id)) {
                values.add(parent);
                values.addAll(result.get(parent));
            }
            result.put(id, Set.copyOf(values));
        }
        return result;
    }

    private Map<String, Set<String>> dominators(List<String> order, Map<String, List<String>> previous) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String id : order) {
            var parents = previous.get(id);
            var values = parents.isEmpty() ? new HashSet<String>() : new HashSet<>(result.get(parents.getFirst()));
            for (String parent : parents) {
                values.retainAll(result.get(parent));
            }
            values.add(id);
            result.put(id, values);
        }
        return result;
    }

    private Map<String, List<String>> neighbors(Map<String, List<Edge>> edges, boolean incoming) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        edges.forEach((id, values) -> result.put(id,
            values.stream().map(edge -> incoming ? edge.source() : edge.target()).toList()));
        return result;
    }

    private Map<String, List<Edge>> freeze(Map<String, List<Edge>> edges) {
        Map<String, List<Edge>> result = new LinkedHashMap<>();
        edges.forEach((id, values) -> result.put(id, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private ApiException invalid(String node, String edge, String field, String message) {
        Map<String, Object> details = new HashMap<>();
        if (node != null) {
            details.put("nodeId", node);
        }
        if (edge != null) {
            details.put("edgeId", edge);
        }
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message, details,
            Map.of("config." + field, List.of(message)));
    }
}
