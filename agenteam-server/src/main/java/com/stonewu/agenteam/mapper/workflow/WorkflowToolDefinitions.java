package com.stonewu.agenteam.mapper.workflow;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作流查询和修改分别声明，写操作使用平台统一确认策略。 */
@Component
public class WorkflowToolDefinitions {
    private final ResourceJson json;

    public WorkflowToolDefinitions(ResourceJson json) {
        this.json = json;
    }

    public List<ToolDefinition> tools() {
        var identity = Map.<String, Object>of("workflowId", text(100));
        var revision = Map.<String, Object>of("type", "string", "pattern", "^[1-9][0-9]{0,18}$");
        return List.of(
            tool("workflow_schema", "读取工作流真实节点模板、变量规则与循环约束。首次创建前调用，不猜测配置格式。", false, Map.of()),
            tool("workflow_list", "查询本人有权查看的工作流及修改版本。", false,
                Map.of("query", text(100), "cursor", text(2048), "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20))),
            tool("workflow_get", "读取有权查看的工作流草稿，修改前读取 revision。", false, identity, "workflowId"),
            tool("workflow_capabilities", "查找当前用户可用的真实智能体或插件版本。不要编造版本编号。", false,
                Map.of("kind", Map.of("type", "string", "enum", List.of("agent", "plugin")), "query", text(100), "cursor", text(2048)), "kind"),
            tool("workflow_dependency", "读取选定版本的真实工具参数或技能选项。工具节点使用返回的工具编号。", false,
                Map.of("versionId", text(100)), "versionId"),
            tool("workflow_create", "创建工作流草稿，不发布、不运行。省略 config 时创建开始和结束节点。", true,
                Map.of("name", text(80), "description", text(500), "config", Map.of("type", "object")), "name"),
            tool("workflow_save", "按当前修改版本保存完整草稿，保留未提交的名称和说明。先读取并与用户核对修改；冲突时重新读取，不能覆盖。", true,
                fields(identity, "revision", revision, "config", Map.of("type", "object"), "name", text(80), "description", text(500)),
                "workflowId", "revision", "config"),
            tool("workflow_patch", "按当前版本批量添加、修改、删除节点或连接。graphPath 是循环节点编号路径，空数组表示外层流程；node 和 edge 是完整对象。删除节点同时删除相关连接。", true,
                fields(identity, "revision", revision, "operations", Map.of("type", "array", "minItems", 1, "maxItems", 100,
                    "items", Map.of("type", "object", "additionalProperties", false, "required", List.of("action"), "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("put_node", "remove_node", "put_edge", "remove_edge")),
                        "graphPath", Map.of("type", "array", "maxItems", 3, "items", text(64)),
                        "node", Map.of("type", "object"), "edge", Map.of("type", "object"), "id", text(64))))),
                "workflowId", "revision", "operations"),
            tool("workflow_validate", "校验已保存或提交的工作流草稿，包括连接、循环和当前依赖权限；不发布、不运行。", false,
                fields(identity, "config", Map.of("type", "object")), "workflowId"));
    }

    private Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private Map<String, Object> fields(Map<String, Object> base, Object... fields) {
        var result = new LinkedHashMap<>(base);
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    private ToolDefinition tool(String name, String description, boolean write, Map<String, Object> properties, String... required) {
        var input = json.tree(Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", List.of(required)));
        var output = json.tree(Map.of("type", "object"));
        String operation = write ? "write" : "read";
        String hash = json.hash(json.tree(Map.of("name", name, "inputSchema", input, "outputSchema", output, "operationClass", operation)));
        return new ToolDefinition(name, description, hash, input, output, json.tree(Map.of("readOnlyHint", !write)), operation, write, false, false, List.of(), 30);
    }
}
