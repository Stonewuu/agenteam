package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Edge;
import com.stonewu.agenteam.model.workflow.entity.WorkflowInvocationState;
import com.stonewu.agenteam.model.workflow.entity.WorkflowInvocationState.NodeProgress;

import java.time.Clock;
import java.util.*;

/**
 * 按真实连接和节点结果推进；并行分支可以独立完成，汇合只在全部已激活前置步骤结束后运行。
 */
public final class WorkflowTraversal {
    public record Ready(List<String> nodes, List<String> skipped) {
    }

    private final WorkflowGraph graph;
    private final WorkflowValues values;
    private final Clock clock;
    private final WorkflowInvocationState identity;
    private final Map<String, NodeProgress> nodes = new LinkedHashMap<>();
    private String status;
    private String errorCode;
    private String errorMessage;

    public WorkflowTraversal(WorkflowGraph graph, WorkflowInvocationState saved, WorkflowValues values, Clock clock) {
        this.graph = graph;
        this.values = values;
        this.clock = clock;
        identity = saved;
        if (!saved.nodes().keySet().equals(graph.nodes().keySet())) {
            throw new IllegalStateException("工作流进度与固定版本节点不一致");
        }
        nodes.putAll(saved.nodes());
        status = saved.status();
        errorCode = saved.errorCode();
        errorMessage = saved.errorMessage();
    }

    public static WorkflowInvocationState initial(WorkflowGraph graph, String id, String version, String session,
                                                  String call, String input) {
        Map<String, NodeProgress> nodes = new LinkedHashMap<>();
        graph.order().forEach(node -> nodes.put(node, NodeProgress.pending()));
        return new WorkflowInvocationState(id, version, session, call, input, "running", nodes, null, null);
    }

    public synchronized Ready ready() {
        if (!status.equals("running")) {
            return new Ready(List.of(), List.of());
        }
        List<String> ready = new ArrayList<>(), skipped = new ArrayList<>();
        for (String id : graph.order()) {
            if (!nodes.get(id).status().equals("pending")) {
                continue;
            }
            var incoming = graph.incoming().get(id);
            if (!incoming.stream().allMatch(edge -> nodes.get(edge.source()).terminal())) {
                continue;
            }
            if (id.equals(graph.startId()) || incoming.stream().anyMatch(this::selected)) {
                ready.add(id);
            } else {
                nodes.put(id,
                    new NodeProgress("skipped", null, null, null, 0, null, clock.millis(), 0, null, null, null, null));
                skipped.add(id);
            }
        }
        return new Ready(List.copyOf(ready), List.copyOf(skipped));
    }

    public synchronized void start(String id, JsonNode input) {
        if (!status.equals("running") || !nodes.get(id).status().equals("pending") || !eligible(id)) {
            throw new IllegalStateException("当前工作流节点尚不能开始");
        }
        long now = clock.millis();
        nodes.put(id,
            new NodeProgress("running", values.write(input), null, null, 1, now, null, 0, now, null, null, null));
    }

    public synchronized boolean complete(String id, JsonNode output, String branch) {
        if (!active(id)) {
            return false;
        }
        var type = graph.nodes().get(id).type();
        if (type.equals("condition") ? !Set.of("true", "false").contains(branch)
            : type.equals("approval") ? !Set.of("approve", "reject").contains(branch) : !"default".equals(branch)) {
            throw new IllegalArgumentException("节点完成时必须选择该类型允许的后续分支");
        }
        finish(id, "completed", values.write(output), branch, null, null);
        if (id.equals(graph.endId())) {
            status = "completed";
        }
        return true;
    }

    public synchronized boolean fail(String id, String code, String message, boolean allowContinue) {
        if (!active(id)) {
            return false;
        }
        boolean continued = allowContinue && graph.nodes().get(id).failurePolicy().equals("continue");
        finish(id, "failed", values.write(values.error(code, message)), continued ? "default" : null, code, message);
        if (!continued) {
            status = "failed";
            errorCode = code;
            errorMessage = message;
            cancelRemaining();
        }
        return true;
    }

    public synchronized boolean suspend(String id) {
        if (!active(id) || !nodes.get(id).status().equals("running")) {
            return false;
        }
        var before = nodes.get(id);
        nodes.put(id,
            new NodeProgress("waiting_approval", before.input(), null, null, before.attempts(), before.startedAt(),
                null,
                before.elapsed(clock.millis()), null, null, null, null));
        return true;
    }

    public synchronized void resume(String id) {
        var before = nodes.get(id);
        if (!status.equals("running") || !before.status().equals("waiting_approval")) {
            throw new IllegalStateException("当前节点没有等待确认");
        }
        nodes.put(id,
            new NodeProgress("running", before.input(), null, null, before.attempts(), before.startedAt(), null,
                before.activeMillis(), clock.millis(), null, null, null));
    }

    public synchronized void scheduleRetry(String id, String code, long retryAt) {
        var before = nodes.get(id);
        if (!active(id) || !graph.nodes().get(id).type()
            .equals("tool") || before.attempts() >= 3 || before.retryAt() != null) {
            throw new IllegalStateException("当前节点不能再次自动重试");
        }
        nodes.put(id,
            new NodeProgress("running", before.input(), null, null, before.attempts() + 1, before.startedAt(), null,
                before.activeMillis(), before.activeSince(), code, null, retryAt));
    }

    public synchronized void startRetry(String id) {
        var before = nodes.get(id);
        if (!active(id) || before.retryAt() == null || before.retryAt() > clock.millis()) {
            throw new IllegalStateException("尚未到达再次读取的时间");
        }
        nodes.put(id,
            new NodeProgress("running", before.input(), null, null, before.attempts(), before.startedAt(), null,
                before.activeMillis(), before.activeSince(), null, null, null));
    }

    public synchronized void cancel() {
        if (!status.equals("running")) {
            return;
        }
        status = "cancelled";
        cancelRemaining();
    }

    public synchronized WorkflowInvocationState snapshot() {
        return new WorkflowInvocationState(identity.id(), identity.versionId(), identity.parentSessionId(),
            identity.toolCallId(),
            identity.input(), status, nodes, errorCode, errorMessage);
    }

    public synchronized Map<String, JsonNode> outputs() {
        Map<String, JsonNode> output = new LinkedHashMap<>();
        nodes.forEach((id, value) -> {
            if (value.output() != null && value.branch() != null) {
                output.put(id, values.read(value.output()));
            }
        });
        return Map.copyOf(output);
    }

    public synchronized NodeProgress node(String id) {
        return nodes.get(id);
    }

    public synchronized boolean hasStepErrors() {
        return nodes.values().stream().anyMatch(node -> node.status().equals("failed") && node.branch() != null);
    }

    public synchronized boolean waiting() {
        return status.equals("running") && nodes.values().stream()
            .anyMatch(node -> node.status().equals("waiting_approval"));
    }

    public WorkflowGraph graph() {
        return graph;
    }

    public JsonNode input() {
        return values.read(identity.input());
    }

    private boolean eligible(String id) {
        var incoming = graph.incoming().get(id);
        return incoming.stream().allMatch(edge -> nodes.get(edge.source()).terminal()) && (id.equals(
            graph.startId()) || incoming.stream().anyMatch(this::selected));
    }

    private boolean selected(Edge edge) {
        var source = nodes.get(edge.source());
        return source.branch() != null && source.branch().equals(edge.branch());
    }

    private boolean active(String id) {
        return status.equals("running") && nodes.containsKey(id) && Set.of("running", "waiting_approval")
            .contains(nodes.get(id).status());
    }

    private void finish(String id, String outcome, String output, String branch, String code, String message) {
        var before = nodes.get(id);
        long now = clock.millis();
        nodes.put(id,
            new NodeProgress(outcome, before.input(), output, branch, before.attempts(), before.startedAt(), now,
                before.elapsed(now), null, code, message, null));
    }

    private void cancelRemaining() {
        for (String id : graph.order()) {
            if (!nodes.get(id).terminal()) {
                finish(id, "cancelled", null, null, null, null);
            }
        }
    }
}
