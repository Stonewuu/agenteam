package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.workflow.request.*;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;

import java.util.Set;
import java.util.HashSet;

/**
 * 按节点类型校验 Java 参数对象；草稿允许尚未选择的依赖，发布时必须补齐。
 */
public final class WorkflowInputValidation {
    private WorkflowInputValidation() {
    }

    public static void validate(JsonNode config, boolean complete) {
        WorkflowNodes.entries(config);
        validateGraph(config, complete);
        if (executionSize(config) > 200) {
            throw ApiException.invalidField("config.nodes", "按循环上限计算，工作流最多执行 200 个步骤，请减少循环次数或步骤。");
        }
    }

    private static int executionSize(JsonNode config) {
        int count = config.path("nodes").size();
        for (var node : config.path("nodes")) {
            if (node.path("type").asText().equals("loop")) {
                var fields = node.path("config");
                int iterations = fields.path("mode").asText().equals("count") ? fields.path("count").asInt() : fields.path("maxIterations").asInt();
                count += iterations * executionSize(fields.path("body"));
            }
        }
        return count;
    }

    private static void validateGraph(JsonNode config, boolean complete) {
        InputValidation.read(config, WorkflowConfigurationInput.class, "config");
        validateGroups(config);
        int index = 0;
        for (var node : config.path("nodes")) {
            String path = "config.nodes[" + index++ + "]";
            Class<?> type = switch (node.path("type").asText()) {
                case "start" -> WorkflowStartNodeInput.class;
                case "agent" -> WorkflowAgentNodeInput.class;
                case "skill" -> WorkflowSkillNodeInput.class;
                case "tool" -> WorkflowToolNodeInput.class;
                case "condition" -> WorkflowConditionNodeInput.class;
                case "transform" -> WorkflowTransformNodeInput.class;
                case "approval" -> WorkflowApprovalNodeInput.class;
                case "parallel" -> WorkflowParallelNodeInput.class;
                case "join" -> WorkflowJoinNodeInput.class;
                case "loop" -> WorkflowLoopNodeInput.class;
                case "end" -> WorkflowEndNodeInput.class;
                default -> throw ApiException.invalidField(path + ".type", "请选择支持的节点类型。");
            };
            InputValidation.read(node, type, path);
            var fields = node.path("config");
            if (node.path("type").asText().equals("loop")) {
                if (fields.path("mode").asText().equals("count")) {
                    InputValidation.required(fields, path + ".config", "count");
                    if (fields.path("count").asInt() > fields.path("maxIterations").asInt()) {
                        throw ApiException.invalidField(path + ".config.count", "循环次数不能超过执行上限。");
                    }
                } else {
                    InputValidation.required(fields, path + ".config", "items");
                    var items = fields.path("items");
                    if (!items.isArray() && !(items.isTextual() && items.asText().matches("\\$\\{[^{}]+}"))) {
                        throw ApiException.invalidField(path + ".config.items", "请选择一个列表变量或填写固定列表。");
                    }
                    if (items.isArray() && items.size() > fields.path("maxIterations").asInt()) {
                        throw ApiException.invalidField(path + ".config.items", "列表条目不能超过循环执行上限。");
                    }
                }
                validateGraph(fields.path("body"), complete);
            }
            if (complete) {
                switch (node.path("type").asText()) {
                    case "agent" -> InputValidation.required(fields, path + ".config", "agentVersionId");
                    case "skill" ->
                        InputValidation.required(fields, path + ".config", "agentVersionId", "skillVersionId");
                    case "tool" -> InputValidation.required(fields, path + ".config", "pluginVersionId");
                    case "parallel" -> InputValidation.required(fields, path + ".config", "joinNodeId");
                    case "join" -> InputValidation.required(fields, path + ".config", "parallelNodeId");
                    default -> {
                    }
                }
            }
            if (node.path("type").asText().equals("condition")) {
                condition(fields.path("condition"), path + ".config.condition", 1, new int[]{0});
            }
            if (node.path("type").asText().equals("transform")) {
                for (var field : fields.path("fields")) {
                    int sources = (field.has("source") ? 1 : 0) + (field.has("template") ? 1 : 0) + (field.has(
                        "literal") ? 1 : 0);
                    if (sources != 1) {
                        throw ApiException.invalidField(path + ".config.fields",
                            "每个转换字段只能选择一个来源、模板或固定值。");
                    }
                }
            }
        }
    }

    /** 显示分组只整理画布，不改变节点的执行顺序与连接规则。 */
    private static void validateGroups(JsonNode graph) {
        var nodes = new HashSet<String>();
        graph.path("nodes").forEach(node -> nodes.add(node.path("nodeId").asText()));
        var groups = new HashSet<String>();
        var groupedNodes = new HashSet<String>();
        int index = 0;
        for (var group : graph.path("groups")) {
            String path = "config.groups[" + index++ + "]";
            if (!groups.add(group.path("groupId").asText()) || nodes.contains(group.path("groupId").asText()) || group.path("name").asText().isBlank()) {
                throw ApiException.invalidField(path, "分组编号不能重复，名称不能为空。");
            }
            for (var member : group.path("nodeIds")) {
                if (!nodes.contains(member.asText()) || !groupedNodes.add(member.asText())) {
                    throw ApiException.invalidField(path + ".nodeIds", "分组只能包含本层已有节点，同一节点不能重复分组。");
                }
            }
        }
    }

    private static void condition(JsonNode value, String path, int depth, int[] comparisons) {
        if (depth > 5 || !value.isObject()) {
            throw ApiException.invalidField(path, "条件需要是对象，且最多嵌套五层。");
        }
        if (value.has("field")) {
            InputValidation.fields(value, path, "field", "operator", "value");
            InputValidation.required(value, path, "field", "operator");
            if (!value.path("field").isTextual() || value.path("field").asText().isEmpty() || value.path("field")
                .asText().length() > 200
                || !Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in", "exists")
                .contains(value.path("operator").asText())
                || ++comparisons[0] > 50) {
                throw ApiException.invalidField(path, "比较字段或操作不正确，比较条件不能超过五十个。");
            }
            if (!value.path("operator").asText().equals("exists") && !value.has("value")) {
                throw ApiException.invalidField(path + ".value", "请填写比较值。");
            }
        } else if (value.has("not")) {
            InputValidation.fields(value, path, "not");
            condition(value.path("not"), path + ".not", depth + 1, comparisons);
        } else {
            String operation = value.has("all") ? "all" : "any";
            InputValidation.fields(value, path, operation);
            JsonNode children = value.path(operation);
            if (!children.isArray() || children.isEmpty() || children.size() > 20) {
                throw ApiException.invalidField(path, "组合条件需要一至二十个子条件。");
            }
            for (var child : children) {
                condition(child, path + "." + operation, depth + 1, comparisons);
            }
        }
    }
}
