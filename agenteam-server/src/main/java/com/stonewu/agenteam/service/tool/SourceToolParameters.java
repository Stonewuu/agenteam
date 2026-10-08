package com.stonewu.agenteam.service.tool;

import java.util.List;
import java.util.Map;

/**
 * 给模型声明工具调用协议；不用于替代后端请求对象和业务校验。
 */
final class SourceToolParameters {
    private SourceToolParameters() {
    }

    static Map<String, Object> knowledge() {
        return object(
            Map.of("query", Map.of("type", "string", "minLength", 2, "maxLength", 500), "limit", integer(1, 8)),
            List.of("query"));
    }

    static Map<String, Object> data() {
        var filter = object(Map.of("field", text(128), "operator", Map.of("type", "string", "enum",
                    List.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in", "is_null")),
                "value", Map.of("type", List.of("string", "number", "boolean", "null", "array"))),
            List.of("field", "operator"));
        var sort = object(
            Map.of("field", text(128), "direction", Map.of("type", "string", "enum", List.of("asc", "desc"))),
            List.of("field", "direction"));
        return object(Map.of("collectionId", text(100), "generation", integer(1, 1000000000),
                "fields", Map.of("type", "array", "items", text(128), "minItems", 1, "maxItems", 100, "uniqueItems", true),
                "filters", Map.of("type", "array", "items", filter, "maxItems", 20),
                "sort", Map.of("type", "array", "items", sort, "maxItems", 3),
                "limit", integer(1, 200), "offset", integer(0, 100000)),
            List.of("collectionId", "generation", "fields", "filters", "sort"));
    }

    private static Map<String, Object> object(Map<String, ?> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }
}
