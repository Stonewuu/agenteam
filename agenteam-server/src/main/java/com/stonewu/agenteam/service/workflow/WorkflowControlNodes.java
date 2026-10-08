package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 控制节点只读取已保存输入与输出，不调用模型或外部工具。
 */
@Component
public class WorkflowControlNodes {
    public record Result(JsonNode output, String branch) {
    }

    private static final Set<String> CONTROL = Set.of("start", "condition", "transform", "parallel", "join", "end");
    private final WorkflowExpressions expressions;
    private final WorkflowConditions conditions;
    private final ToolSchemaValidation schemas;
    private final ObjectMapper json;

    public WorkflowControlNodes(WorkflowExpressions expressions, WorkflowConditions conditions,
                                ToolSchemaValidation schemas, ObjectMapper json) {
        this.expressions = expressions;
        this.conditions = conditions;
        this.schemas = schemas;
        this.json = json;
    }

    public boolean handles(Node node) {
        return CONTROL.contains(node.type());
    }

    public void validateInput(WorkflowTraversal workflow) {
        validateInput(workflow.graph(), workflow.input());
    }

    public void validateInput(WorkflowGraph graph, JsonNode input) {
        var schema = graph.nodes().get(graph.startId()).config().path("inputSchema");
        if (input == null || !input.isObject() || !schemas.compile(schema).validate(input).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_INPUT_INVALID",
                "输入不符合工作流开始节点要求，请检查必填字段与数据类型。");
        }
    }

    public JsonNode input(WorkflowTraversal workflow, Node node) {
        var config = node.config();
        if (config.has("inputMapping")) {
            return expressions.resolve(config.path("inputMapping"), workflow.input(), workflow.outputs());
        }
        if (node.type().equals("end")) {
            return expressions.resolve(config.path("outputMapping"), workflow.input(), workflow.outputs());
        }
        return Set.of("parallel", "join").contains(node.type()) ? json.createObjectNode() : workflow.input();
    }

    public Result execute(WorkflowTraversal workflow, Node node, JsonNode input) {
        return switch (node.type()) {
            case "start" -> {
                validateInput(workflow);
                yield new Result(input, "default");
            }
            case "condition" -> {
                boolean matches = conditions.evaluate(node.config().path("condition"), workflow.input(),
                    workflow.outputs());
                yield new Result(json.createObjectNode().put("matched", matches), Boolean.toString(matches));
            }
            case "transform" ->
                new Result(expressions.transform(node.config().path("fields"), workflow.input(), workflow.outputs()),
                    "default");
            case "parallel", "join", "end" -> new Result(input, "default");
            default -> throw new IllegalArgumentException("当前节点需要实际智能体、工具或用户确认");
        };
    }
}
