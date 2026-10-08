package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 声明知识和数据工具的调用参数，实际参数由对应接口与业务校验。
 */
@Component
public class SourceToolDefinitions {
    private final ToolDefinition knowledge;
    private final List<ToolDefinition> data;

    public SourceToolDefinitions(ResourceJson json) {
        knowledge = definition("knowledge_search",
            "检索这份知识库中的真实资料，返回文档、处理版本、页码或章节及原文片段。只在找到资料时使用返回的来源作引用。",
            json.tree(SourceToolParameters.knowledge()), List.of(), json);
        var hidden = new ArrayList<String>();
        for (int index = 0; index < 20; index++) {
            hidden.add("/filters/" + index + "/value");
        }
        data = List.of(definition("data_collections",
                "列出本数据源当前可查询的集合、处理版本和允许字段。查询前先读取此列表，不要猜测字段或集合编号。",
                json.tree(Map.of("type", "object", "properties",
                    Map.of("cursor", Map.of("type", "string", "minLength", 1, "maxLength", 2048),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 5)), "additionalProperties", false)),
                List.of(), json),
            definition("data_query",
                "按照明确的集合编号、处理版本和允许字段读取数据。只允许结构化条件，不能提交 SQL、地址或请求头。大整数和小数保留为精确字符串。",
                json.tree(SourceToolParameters.data()), hidden, json));
    }

    public List<ToolDefinition> forKind(String kind) {
        return switch (kind) {
            case "knowledge" -> List.of(knowledge);
            case "data" -> data;
            default -> List.of();
        };
    }

    private ToolDefinition definition(String name, String description, JsonNode input, List<String> hidden,
                                      ResourceJson json) {
        String hash = json.hash(json.tree(Map.of("name", name, "input", input, "operation", "read")));
        return new ToolDefinition(name, description, hash, input, null, json.tree(Map.of("readOnlyHint", true)), "read",
            false, false, true, List.copyOf(hidden), 10);
    }
}
