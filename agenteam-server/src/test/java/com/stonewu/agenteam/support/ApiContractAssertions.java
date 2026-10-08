package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 仅在测试中将接口响应与开发文档对照，不参与应用启动或请求校验。
 */
public final class ApiContractAssertions {
    private final ObjectMapper json = new ObjectMapper();
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
        builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
    private final Map<String, Schema> rules = new HashMap<>();
    private JsonNode definitions;

    public void validate(String name, JsonNode value) {
        var errors = rules.computeIfAbsent(name, this::rule).validate(value);
        if (!errors.isEmpty()) {
            throw new AssertionError("接口响应与测试定义中的 " + name + " 不一致：" + errors);
        }
    }

    private Schema rule(String name) {
        if (definitions == null) {
            definitions = ApiContractDocument.load(json).required("components").required("schemas");
            var pending = new ArrayDeque<JsonNode>();
            pending.add(definitions);
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (node.isObject() && node.has("$ref")) {
                    ((ObjectNode) node).put("$ref", node.path("$ref").asText().replace("#/components/schemas/", "#/$defs/"));
                }
                if (node.isContainerNode()) {
                    node.forEach(pending::addLast);
                }
            }
        }
        if (!definitions.has(name)) {
            throw new IllegalArgumentException("接口定义缺少测试所用的结构");
        }
        var root = json.createObjectNode();
        root.set("$defs", definitions);
        root.put("$ref", "#/$defs/" + name);
        return registry.getSchema(root);
    }
}
