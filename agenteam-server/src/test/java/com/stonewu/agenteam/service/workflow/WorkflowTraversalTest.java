package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.workflow.entity.WorkflowInvocationState;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTraversalTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowValues values = new WorkflowValues(json);
    private final WorkflowExpressions expressions = new WorkflowExpressions(json);
    private final WorkflowConditions conditions = new WorkflowConditions(expressions);
    private final ToolSchemaValidation schemas = new ToolSchemaValidation();
    private final WorkflowGraphValidator graphs = new WorkflowGraphValidator(schemas, expressions, conditions);
    private final WorkflowControlNodes controls = new WorkflowControlNodes(expressions, conditions, schemas, json);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void parallelBranchesWaitForTheActivePathAndResumeWithoutRepeatingCompletedNodes() throws Exception {
        var flow = parallel();
        var traversal = traversal(flow, Map.of("selected", true));
        execute(traversal, "start");
        execute(traversal, "parallel");
        assertEquals(List.of("choose", "right"), traversal.ready().nodes());
        traversal.start("right", json.createObjectNode());
        execute(traversal, "choose");
        var ready = traversal.ready();
        assertEquals(List.of("left"), ready.nodes());
        assertEquals(List.of("unused"), ready.skipped());
        execute(traversal, "left");
        assertTrue(traversal.ready().nodes().isEmpty(), "右侧真实执行尚未完成，不能提前汇合");
        var saved = json.readValue(json.writeValueAsString(traversal.snapshot()), WorkflowInvocationState.class);
        var restored = new WorkflowTraversal(traversal.graph(), saved, values, clock);
        assertTrue(restored.ready().nodes().isEmpty());
        restored.complete("right", json.valueToTree(Map.of("result", "右侧结果")), "default");
        assertEquals(List.of("join"), restored.ready().nodes());
        execute(restored, "join");
        execute(restored, "end");
        assertEquals("completed", restored.snapshot().status());
        assertEquals("左侧结果", restored.outputs().get("end").path("left").asText());
        assertEquals("右侧结果", restored.outputs().get("end").path("right").asText());
        assertEquals(1, restored.node("left").attempts());
        assertEquals(0, restored.node("unused").attempts());
    }

    @Test
    void unselectedNestedParallelRegionNeverStartsOrBlocksTheSelectedPath() {
        var nodes = new ArrayList<JsonNode>(List.of(start(), choose("choice", "input.selected"),
            node("parallel", "parallel", Map.of("joinNodeId", "join")), transform("left", "左"), transform("right", "右"),
            node("join", "join", Map.of("parallelNodeId", "parallel")), transform("chosen", "已选择"), end(Map.of("result", "${steps.chosen.output.result}"))));
        var graph = graph(nodes, List.of(edge("start", "choice"), edge("choice", "parallel", "true"), edge("choice", "chosen", "false"),
            edge("parallel", "left"), edge("parallel", "right"), edge("left", "join"), edge("right", "join"), edge("join", "end"), edge("chosen", "end")));
        var traversal = traversal(graph, Map.of("selected", false));
        execute(traversal, "start");
        execute(traversal, "choice");
        var ready = traversal.ready();
        assertEquals(List.of("chosen"), ready.nodes());
        assertEquals(4, ready.skipped().size());
        execute(traversal, "chosen");
        execute(traversal, "end");
        assertEquals("已选择", traversal.outputs().get("end").path("result").asText());
        assertEquals(0, traversal.node("parallel").attempts());
    }

    @Test
    void failuresContinueOnlyWhenExplicitlyAllowedAndStopsRejectLateCompletions() {
        var worker = transform("work", "未使用");
        worker.put("failurePolicy", "continue");
        var graph = graph(List.of(start(), worker, end(Map.of("result", "${steps.work.output}"))), List.of(edge("start", "work"), edge("work", "end")));
        var traversal = traversal(graph, Map.of());
        execute(traversal, "start");
        traversal.start("work", json.createObjectNode());
        assertTrue(traversal.fail("work", "READ_UNAVAILABLE", "当前内容无法读取。", true));
        execute(traversal, "end");
        assertTrue(traversal.hasStepErrors());
        assertEquals("READ_UNAVAILABLE", traversal.outputs().get("end").at("/result/error/code").asText());

        var fatal = traversal(graph, Map.of());
        execute(fatal, "start");
        fatal.start("work", json.createObjectNode());
        fatal.fail("work", "TOOL_RESULT_UNKNOWN", "外部写入结果尚不能确定。", false);
        assertEquals("failed", fatal.snapshot().status());
        assertEquals("cancelled", fatal.node("end").status());
        assertFalse(fatal.complete("work", json.createObjectNode(), "default"));
        assertTrue(fatal.ready().nodes().isEmpty());

        var cancelled = traversal(parallel(), Map.of("selected", true));
        execute(cancelled, "start");
        execute(cancelled, "parallel");
        cancelled.start("right", json.createObjectNode());
        cancelled.cancel();
        assertFalse(cancelled.complete("right", json.createObjectNode(), "default"));
        assertTrue(cancelled.ready().nodes().isEmpty());
        assertEquals("cancelled", cancelled.node("end").status());
    }

    @Test
    void approvalWaitDoesNotConsumeNodeActiveTimeAndRejectSelectsOnlyTheRejectionPath() {
        var graph = graph(List.of(start(), node("approval", "approval", Map.of("title", "确认发布", "description", "请核对内容", "inputMapping", Map.of())),
                transform("approved", "同意"), transform("rejected", "拒绝"), end(Map.of("result", "${steps.rejected.output.result}"))),
            List.of(edge("start", "approval"), edge("approval", "approved", "approve"), edge("approval", "rejected", "reject"),
                edge("approved", "end"), edge("rejected", "end")));
        var traversal = traversal(graph, Map.of());
        execute(traversal, "start");
        traversal.start("approval", json.createObjectNode());
        var afterWork = new WorkflowTraversal(traversal.graph(), traversal.snapshot(), values, Clock.offset(clock, Duration.ofMillis(120)));
        afterWork.suspend("approval");
        assertTrue(afterWork.waiting());
        assertTrue(afterWork.ready().nodes().isEmpty());
        var resumed = new WorkflowTraversal(traversal.graph(), afterWork.snapshot(), values, Clock.offset(clock, Duration.ofHours(2)));
        resumed.resume("approval");
        resumed.complete("approval", json.valueToTree(Map.of("decision", "reject")), "reject");
        assertEquals(120, resumed.node("approval").activeMillis());
        assertEquals(List.of("rejected"), resumed.ready().nodes());
        assertEquals("skipped", resumed.node("approved").status());
        execute(resumed, "rejected");
        execute(resumed, "end");
        assertEquals("拒绝", resumed.outputs().get("end").path("result").asText());
    }

    @Test
    void invalidInputsAndMissingSelectedOutputsFailBeforeAnyDependentExecution() {
        var required = start();
        ((ObjectNode) required.get("config")).set("inputSchema", json.valueToTree(Map.of("type", "object", "required", List.of("text"))));
        var linear = graph(List.of(required, end(Map.of())), List.of(edge("start", "end")));
        var traversal = traversal(linear, Map.of());
        assertEquals("WORKFLOW_INPUT_INVALID", assertThrows(ApiException.class, () -> controls.validateInput(traversal)).code());
        assertEquals(0, traversal.node("start").attempts());
        var missing = traversal(parallel(), Map.of("selected", false));
        execute(missing, "start");
        execute(missing, "parallel");
        execute(missing, "choose");
        missing.ready();
        execute(missing, "unused");
        execute(missing, "right");
        execute(missing, "join");
        assertEquals("WORKFLOW_INPUT_MISSING", assertThrows(ApiException.class, () -> controls.input(missing, missing.graph().nodes().get("end"))).code());
        assertEquals(0, missing.node("end").attempts());
    }

    private void execute(WorkflowTraversal traversal, String id) {
        assertTrue(traversal.ready().nodes().contains(id), "节点必须先具备执行条件：" + id);
        var node = traversal.graph().nodes().get(id);
        var input = controls.input(traversal, node);
        traversal.start(id, input);
        var result = controls.execute(traversal, node, input);
        assertTrue(traversal.complete(id, result.output(), result.branch()));
    }

    private WorkflowTraversal traversal(JsonNode config, Map<String, Object> input) {
        var graph = graphs.compile(config);
        return new WorkflowTraversal(graph, WorkflowTraversal.initial(graph, "invocation", "version", "session", null,
            values.write(json.valueToTree(input))), values, clock);
    }

    private ObjectNode parallel() {
        return graph(List.of(start(), node("parallel", "parallel", Map.of("joinNodeId", "join")), choose("choose", "input.selected"),
                transform("left", "左侧结果"), transform("unused", "不应出现"), transform("right", "右侧结果"),
                node("join", "join", Map.of("parallelNodeId", "parallel")), end(Map.of("left", "${steps.left.output.result}", "right", "${steps.right.output.result}"))),
            List.of(edge("start", "parallel"), edge("parallel", "choose"), edge("parallel", "right"), edge("choose", "left", "true"), edge("choose", "unused", "false"),
                edge("left", "join"), edge("unused", "join"), edge("right", "join"), edge("join", "end")));
    }

    private ObjectNode start() {
        return node("start", "start", Map.of("inputSchema", Map.of("type", "object")));
    }

    private ObjectNode end(Map<String, Object> output) {
        return node("end", "end", Map.of("outputMapping", output));
    }

    private ObjectNode choose(String id, String field) {
        return node(id, "condition", Map.of("condition", Map.of("field", field, "operator", "eq", "value", true)));
    }

    private ObjectNode transform(String id, String text) {
        return node(id, "transform", Map.of("fields", List.of(Map.of("target", "result", "literal", text))));
    }

    private ObjectNode node(String id, String type, Map<String, Object> config) {
        return json.valueToTree(Map.of("nodeId", id, "type", type, "name", id, "position", Map.of("x", 0, "y", 0),
            "timeoutSeconds", 60, "failurePolicy", "stop", "config", config));
    }

    private JsonNode edge(String source, String target) {
        return edge(source, target, "default");
    }

    private JsonNode edge(String source, String target, String branch) {
        return json.valueToTree(Map.of("edgeId", source + "-" + target, "source", source, "target", target, "branch", branch));
    }

    private ObjectNode graph(List<JsonNode> nodes, List<JsonNode> edges) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", edges));
    }
}
