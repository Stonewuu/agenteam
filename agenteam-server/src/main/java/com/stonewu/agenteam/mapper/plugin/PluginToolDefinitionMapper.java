package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 保存原始参数结构和行为提示，远程声明不会自动取得免确认或重试资格。
 */
@Component
public class PluginToolDefinitionMapper {
    private final ResourceJson json;
    private final ToolSchemaValidation schemas;

    public PluginToolDefinitionMapper(ResourceJson json, ToolSchemaValidation schemas) {
        this.json = json;
        this.schemas = schemas;
    }

    public List<ToolDefinition> remote(List<JsonNode> source, int timeoutSeconds) {
        var names = new HashSet<String>();
        if (source.size() > 100) {
            throw invalid();
        }
        return source.stream().map(tool -> {
            String name = tool.path("name").asText();
            if (!name.matches("[A-Za-z0-9_.-]{1,128}") || !names.add(name)
                || tool.path("execution").path("taskSupport").asText().equals("required")) {
                throw invalid();
            }
            JsonNode input = tool.path("inputSchema"), output = tool.get("outputSchema"), annotations = tool.get(
                "annotations");
            schemas.compile(input);
            if (output != null && !output.isNull()) {
                schemas.compile(output);
            } else {
                output = json.tree(null);
            }
            if (annotations == null) {
                annotations = json.tree(Map.of());
            }
            if (!annotations.isObject()) {
                throw invalid();
            }
            String hash = json.hash(json.tree(
                Map.of("name", name, "inputSchema", input, "outputSchema", output, "annotations", annotations)));
            // MCP（模型上下文协议）的顶层展示标题优先于 annotations.title；不改变调用结构摘要。
            ObjectNode displayAnnotations = annotations.deepCopy();
            JsonNode title = tool.get("title");
            if (title != null && title.isTextual() && !title.asText().isBlank()) {
                displayAnnotations.put("title", title.asText().strip());
            }
            String description = tool.path("description").asText("");
            if (description.length() > 500) {
                description = description.substring(0, 500);
            }
            return new ToolDefinition(name, description, hash, input.deepCopy(), output.deepCopy(), displayAnnotations,
                "unknown", false, false, false, List.of(), timeoutSeconds);
        }).toList();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MCP_TOOL_STRUCTURE_INVALID",
            "工具清单包含不支持的名称、重复条目或执行方式。");
    }
}
