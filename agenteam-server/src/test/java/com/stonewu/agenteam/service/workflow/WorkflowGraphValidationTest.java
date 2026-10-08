package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphValidationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowExpressions expressions = new WorkflowExpressions(json);
    private final WorkflowGraphValidator graphs = new WorkflowGraphValidator(new ToolSchemaValidation(), expressions, new WorkflowConditions(expressions));

    @Test
    void followsConnectionsInsteadOfCanvasOrArrayOrderAndKeepsItsOwnConfiguration() {
        var flow = graph(List.of(end(), transform("work"), start()), List.of(edge("start", "work"), edge("work", "end")));
        var plan = graphs.compile(flow);
        assertEquals(List.of("start", "work", "end"), plan.order());
        assertTrue(plan.ancestors().get("end").contains("work"));
        ((ObjectNode) flow.at("/nodes/1/config/fields/0")).put("literal", "外部修改");
        assertEquals("work", plan.nodes().get("work").config().at("/fields/0/literal").asText());
    }

    @Test
    void rejectsCyclesIsolatedNodesDuplicateConnectionsAndMoreThanFiftyNodes() {
        var cycle = linear();
        ((ArrayNode) cycle.get("edges")).add(edge("work", "start"));
        reject(cycle);
        var isolated = linear();
        ((ArrayNode) isolated.get("nodes")).add(transform("unused"));
        reject(isolated);
        var duplicate = linear();
        ((ArrayNode) duplicate.get("edges")).add(edge("start", "work"));
        reject(duplicate);
        List<JsonNode> nodes = new ArrayList<>();
        List<JsonNode> edges = new ArrayList<>();
        nodes.add(start());
        String previous = "start";
        for (int i = 0; i < 49; i++) {
            String id = "step" + i;
            nodes.add(transform(id));
            edges.add(edge(previous, id));
            previous = id;
        }
        nodes.add(end());
        edges.add(edge(previous, "end"));
        reject(graph(nodes, edges));
    }

    @Test
    void acceptsExplicitParallelBranchesAndRejectsEarlyMergeOrMissingJoin() {
        assertEquals(6, graphs.compile(parallel()).order().size());
        var merge = parallel();
        ((ArrayNode) merge.get("nodes")).add(transform("merge"));
        merge.set("edges", json.valueToTree(List.of(edge("start", "parallel"), edge("parallel", "left"), edge("parallel", "right"),
            edge("left", "merge"), edge("right", "merge"), edge("merge", "join"), edge("join", "end"))));
        var error = reject(merge);
        assertTrue(error.details().containsKey("nodeId"));
        var missing = parallel();
        ((ObjectNode) missing.at("/nodes/1/config")).put("joinNodeId", "end");
        reject(missing);
        var escaping = parallel();
        ((ObjectNode) escaping.at("/edges/3")).put("target", "end");
        reject(escaping);
    }

    @Test
    void rejectsImplicitParallelismAndReadingAnotherUnfinishedBranch() {
        var fork = linear();
        ((ArrayNode) fork.get("nodes")).add(transform("another"));
        ((ArrayNode) fork.get("edges")).add(edge("work", "another")).add(edge("another", "end"));
        reject(fork);
        var sibling = parallel();
        ((ObjectNode) sibling.at("/nodes/2/config/fields/0")).remove("literal");
        ((ObjectNode) sibling.at("/nodes/2/config/fields/0")).put("source", "steps.right.output.answer");
        assertEquals("left", reject(sibling).details().get("nodeId"));
    }

    @Test
    void rejectsIncompleteTemplatesFutureResultsAndDuplicateOutputFields() {
        var incomplete = linear();
        var field = (ObjectNode) incomplete.at("/nodes/1/config/fields/0");
        field.remove("literal");
        field.put("template", "结果：${input.title");
        reject(incomplete);
        field.put("template", "${steps.end.output.result}");
        reject(incomplete);
        field.put("template", "${input.title}");
        assertEquals(3, graphs.compile(incomplete).order().size());
        ((ArrayNode) incomplete.at("/nodes/1/config/fields")).add(json.valueToTree(Map.of("target", "answer", "literal", "覆盖")));
        reject(incomplete);
    }

    @Test
    void validatesStartSchemaWithoutAllowingRemoteOrRecursiveDefinitions() {
        var remote = linear();
        ((ObjectNode) remote.at("/nodes/0/config/inputSchema")).put("$ref", "https://invalid.example/schema.json");
        reject(remote);
        var recursive = linear();
        var schema = (ObjectNode) recursive.at("/nodes/0/config/inputSchema");
        schema.put("$ref", "#/$defs/self");
        schema.set("$defs", json.valueToTree(Map.of("self", Map.of("$ref", "#/$defs/self"))));
        reject(recursive);
        var wrong = linear();
        ((ObjectNode) wrong.at("/nodes/0/config/inputSchema")).put("type", "unknown");
        reject(wrong);
    }

    @Test
    void enforcesConditionDepthAndLeafCountBeforeExecution() {
        JsonNode condition = json.valueToTree(Map.of("field", "input.ready", "operator", "exists"));
        for (int i = 0; i < 4; i++) {
            condition = json.valueToTree(Map.of("not", condition));
        }
        assertEquals(5, graphs.compile(condition(condition)).order().size());
        reject(condition(json.valueToTree(Map.of("not", condition))));
        List<JsonNode> groups = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            groups.add(json.valueToTree(Map.of("all", existenceConditions(26))));
        }
        reject(condition(json.valueToTree(Map.of("all", groups))));
    }

    private List<JsonNode> existenceConditions(int count) {
        List<JsonNode> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(json.valueToTree(Map.of("field", "input.ready", "operator", "exists")));
        }
        return result;
    }

    private ObjectNode condition(JsonNode condition) {
        return graph(List.of(start(), node("choose", "condition", Map.of("condition", condition)), transform("left"), transform("right"), end()),
            List.of(edge("start", "choose"), edge("choose", "left", "true"), edge("choose", "right", "false"), edge("left", "end"), edge("right", "end")));
    }

    private ObjectNode parallel() {
        return graph(List.of(start(), node("parallel", "parallel", Map.of("joinNodeId", "join")), transform("left"), transform("right"), node("join", "join", Map.of("parallelNodeId", "parallel")), end()),
            List.of(edge("start", "parallel"), edge("parallel", "left"), edge("parallel", "right"), edge("left", "join"), edge("right", "join"), edge("join", "end")));
    }

    private ObjectNode linear() {
        return graph(List.of(start(), transform("work"), end()), List.of(edge("start", "work"), edge("work", "end")));
    }

    private ObjectNode graph(List<? extends JsonNode> nodes, List<? extends JsonNode> edges) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", edges));
    }

    private ObjectNode start() {
        return node("start", "start", Map.of("inputSchema", Map.of("type", "object")));
    }

    private ObjectNode end() {
        return node("end", "end", Map.of("outputMapping", Map.of("text", "完成")));
    }

    private ObjectNode transform(String id) {
        return node(id, "transform", Map.of("fields", List.of(Map.of("target", "answer", "literal", id))));
    }

    private ObjectNode node(String id, String type, Object config) {
        return json.valueToTree(Map.of("nodeId", id, "type", type, "name", id, "position", Map.of("x", 500, "y", -100), "timeoutSeconds", 60, "failurePolicy", "stop", "config", config));
    }

    private ObjectNode edge(String from, String to) {
        return edge(from, to, "default");
    }

    private ObjectNode edge(String from, String to, String branch) {
        return json.valueToTree(Map.of("edgeId", from + "-" + to + "-" + branch, "source", from, "target", to, "branch", branch));
    }

    private ApiException reject(JsonNode flow) {
        var error = assertThrows(ApiException.class, () -> graphs.compile(flow));
        assertEquals("VALIDATION_FAILED", error.code());
        assertFalse(error.fieldErrors().isEmpty());
        return error;
    }
}
