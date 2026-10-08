package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.networknt.schema.*;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 工具参数结构只引用本文件；使用线性耗时的正则匹配，拒绝循环和过深的结构。
 */
@Component
public class ToolSchemaValidation {
    private static final Set<String> MAPS = Set.of("properties", "patternProperties", "$defs", "definitions",
        "dependentSchemas", "dependencies");
    private static final Set<String> ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> CHILDREN = Set.of("items", "additionalProperties", "unevaluatedProperties",
        "contains", "propertyNames",
        "not", "if", "then", "else", "additionalItems", "unevaluatedItems", "contentSchema");
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
        builder -> builder
            .schemaLoader(loader -> loader.fetchRemoteResources(false))
            .schemaRegistryConfig(SchemaRegistryConfig.builder().regularExpressionFactory(regex -> {
                if (regex.length() > 1024) {
                    throw invalid();
                }
                Pattern compiled = Pattern.compile(regex);
                return value -> compiled.matcher(value).find();
            }).build()));

    public Schema compile(JsonNode schema) {
        try {
            if (schema == null || !schema.isObject() || schema.toString()
                .getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
                throw invalid();
            }
            walk(schema, schema, Collections.newSetFromMap(new IdentityHashMap<>()), new int[]{0}, 0);
            String dialect = schema.path("$schema").asText(SpecificationVersion.DRAFT_2020_12.getDialectId());
            if (SpecificationVersion.fromDialectId(dialect).isEmpty()) {
                throw invalid();
            }
            if (!registry.getSchema(SchemaLocation.of(dialect)).validate(schema).isEmpty()) {
                throw invalid();
            }
            return registry.getSchema(schema);
        } catch (ApiException failure) {
            throw failure;
        } catch (PatternSyntaxException failure) {
            throw invalid(failure);
        } catch (RuntimeException failure) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TOOL_SCHEMA_VALIDATION_FAILED",
                "本次执行未能完成，请稍后重试。", failure);
        }
    }

    public void arguments(JsonNode schema, JsonNode value) {
        requireBoundedArguments(value);
        if (!compile(schema).validate(value).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENTS_INVALID",
                "工具参数不符合已发布的要求，未执行本次操作。");
        }
    }

    public void requireBoundedArguments(JsonNode value) {
        if (value == null || !value.isObject() || value.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024
            || !bounded(value, 0, new int[]{0})) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENTS_INVALID",
                "工具参数不符合已发布的要求，未执行本次操作。");
        }
    }

    private boolean bounded(JsonNode value, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 10000) {
            return false;
        }
        if (value.isNumber() && (Math.abs((long) value.decimalValue().scale()) > 1000 || value.decimalValue()
            .precision() > 1000)) {
            return false;
        }
        for (var child : value) {
            if (!bounded(child, depth + 1, count)) {
                return false;
            }
        }
        return true;
    }

    public void result(JsonNode schema, JsonNode value) {
        if (schema != null && !schema.isNull() && (value == null || !compile(schema).validate(value).isEmpty())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", "工具返回的内容不符合已发布的结构。");
        }
    }

    private void walk(JsonNode root, JsonNode node, Set<JsonNode> path, int[] count, int depth) {
        if (++count[0] > 10000 || depth > 32 || !path.add(node)) {
            throw invalid();
        }
        try {
            if (node.isBoolean()) {
                return;
            }
            if (!node.isObject() || node.has("$id") || node.has("$dynamicRef") || node.has("$recursiveRef")) {
                throw invalid();
            }
            if (node.has("$ref")) {
                String ref = node.path("$ref").asText();
                if (!ref.startsWith("#/") || ref.contains("%")) {
                    throw invalid();
                }
                JsonNode target = root.at(ref.substring(1));
                if (target.isMissingNode()) {
                    throw invalid();
                }
                walk(root, target, path, count, depth + 1);
            }
            if (node.has("pattern")) {
                if (node.path("pattern").asText().length() > 1024) {
                    throw invalid();
                }
                Pattern.compile(node.path("pattern").asText());
            }
            node.fields().forEachRemaining(field -> {
                String key = field.getKey();
                JsonNode child = field.getValue();
                if (MAPS.contains(key)) {
                    child.fields().forEachRemaining(entry -> {
                        if (key.equals("patternProperties")) {
                            if (entry.getKey().length() > 1024) {
                                throw invalid();
                            }
                            Pattern.compile(entry.getKey());
                        }
                        if (!key.equals("dependencies") || !entry.getValue().isArray()) {
                            walk(root, entry.getValue(), path, count, depth + 1);
                        }
                    });
                } else if (ARRAYS.contains(key) || (key.equals("items") && child.isArray())) {
                    child.forEach(item -> walk(root, item, path, count, depth + 1));
                } else if (CHILDREN.contains(key)) {
                    walk(root, child, path, count, depth + 1);
                }
            });
        } finally {
            path.remove(node);
        }
    }

    private static ApiException invalid() {
        return invalid(null);
    }

    private static ApiException invalid(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MCP_TOOL_STRUCTURE_INVALID",
            "本次执行未能完成，请稍后重试。", cause);
    }
}
