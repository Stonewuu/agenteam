package com.stonewu.agenteam.mapper.workflow;

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
            node("start", "开始", Map.of("inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string", "title", "任务内容"))))),
            node("end", "结束", Map.of("outputMapping", Map.of("text", "${input.text}")))),
            "edges", List.of(Map.of("edgeId", "start_end", "source", "start", "target", "end", "branch", "default"))));
    }

    public JsonNode schema() {
        var templates = new ArrayList<Object>();
        initial().path("nodes").forEach(templates::add);
        templates.add(node("agent", "智能体", Map.of("agentVersionId", "从可用能力选择", "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("skill", "技能", Map.of("agentVersionId", "从可用能力选择", "skillVersionId", "从所选智能体选择", "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("tool", "工具", Map.of("pluginVersionId", "从可用能力选择", "toolId", "从插件工具选择", "inputMapping", Map.of())));
        templates.add(node("condition", "判断", Map.of("condition", Map.of("field", "input.text", "operator", "exists"))));
        templates.add(node("transform", "数据整理", Map.of("fields", List.of(Map.of("target", "text", "source", "input.text")))));
        templates.add(node("approval", "人工确认", Map.of("title", "请确认是否继续", "description", "", "inputMapping", Map.of("text", "${input.text}"))));
        templates.add(node("parallel", "并行", Map.of("joinNodeId", "join")));
        templates.add(node("join", "汇合", Map.of("parallelNodeId", "parallel")));
        ObjectNode body = initial().deepCopy();
        ((ObjectNode) body.path("nodes").get(0).path("config")).set("inputSchema", json.tree(Map.of("type", "object")));
        ((ObjectNode) body.path("nodes").get(1).path("config")).set("outputMapping", json.tree(Map.of("result", "${input.item}")));
        templates.add(node("loop", "循环", Map.of("mode", "count", "count", 3, "maxIterations", 20, "items", "${input.items}", "body", body)));
        return json.tree(Map.of("initial", initial(), "nodeTemplates", templates,
            "rules", List.of("每层恰好一个开始与结束，全部节点从开始可达并最终到达结束；禁止回连，循环使用 loop.body。",
                "condition 必须有 true/false 两路；approval 必须有 approve/reject 两路；parallel 有二至四路 default 并与 join 相互指定。其他节点有一条 default，结束无后续。",
                "变量写为 ${input.字段} 或 ${steps.上游节点编号.output.字段}，只能引用上游结果。条件 field 和转换 source 使用不带 ${} 的变量路径。",
                "循环 mode=count 时重复 count 次；mode=items 时 items 为列表或变量，超出 maxIterations 报错；上限 20，嵌套三层，总节点 50，按上限计算执行步骤最多 200。",
                "循环内部 input.item 为本项，input.index 从零开始，input.number 从一开始，input.parent 为循环前输入，input.previous 为上次结束输出。循环输出含 results 列表和 iterations 次数。",
                "节点编号须以英文字母开头，仅含字母、数字、下划线和短横线；工具和智能体使用真实固定版本。先保存草稿再调用 workflow_validate 修正错误。",
                "groups 为可选的画布分组，含 groupId、name、nodeIds；每组至少两个本层节点，每个节点只能属于一个组，最多二十五组。分组不改变执行顺序，编辑时保留已有分组。")));
    }

    private Map<String, Object> node(String type, String name, Map<String, Object> config) {
        return Map.of("nodeId", type, "type", type, "name", name, "position", Map.of("x", type.equals("end") ? 325 : 40, "y", 80),
            "timeoutSeconds", 120, "failurePolicy", "stop", "config", config);
    }

    public JsonNode patch(JsonNode original, JsonNode operations) {
        if (!operations.isArray() || operations.isEmpty() || operations.size() > 100) {
            throw ApiException.invalidField("operations", "请提交一至一百项修改。");
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
                    throw ApiException.invalidField("graphPath", "指定的循环步骤区域不存在。");
                }
                graph = body;
            }
            String action = operation.path("action").asText();
            if (!Set.of("put_node", "remove_node", "put_edge", "remove_edge").contains(action)) {
                throw ApiException.invalidField("action", "请选择有效的工作流修改操作。");
            }
            boolean node = action.endsWith("node"), remove = action.startsWith("remove");
            String field = node ? "nodes" : "edges", key = node ? "nodeId" : "edgeId";
            JsonNode value = operation.path(node ? "node" : "edge");
            String id = remove ? operation.path("id").asText() : value.path(key).asText();
            if (id.isBlank() || !remove && !value.isObject()) {
                throw ApiException.invalidField("operations", "请提供节点或连接的完整内容及编号。");
            }
            var entries = new LinkedHashMap<String, JsonNode>();
            graph.path(field).forEach(item -> entries.put(item.path(key).asText(), item));
            if (remove) {
                if (entries.remove(id) == null) {
                    throw ApiException.invalidField("id", "要删除的节点或连接不存在，请重新读取草稿。");
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
