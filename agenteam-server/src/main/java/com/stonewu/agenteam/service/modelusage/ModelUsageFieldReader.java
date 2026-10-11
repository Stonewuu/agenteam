package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读取明确列出的数字字段，异常字段不能把消息正文或密钥带入计量证据。
 */
final class ModelUsageFieldReader {
    private static final long MAX_TOKENS = 100_000_000_000L;
    final Map<String, String> states = new LinkedHashMap<>();
    final Map<String, Object> evidence = new LinkedHashMap<>();
    final List<String> problems = new ArrayList<>();
    final List<String> unsupported = new ArrayList<>();
    private final JsonNode source;
    private final Set<String> notApplicable;

    ModelUsageFieldReader(JsonNode source, Set<String> notApplicable) {
        this.source = source;
        this.notApplicable = notApplicable;
    }

    Long read(String field, String path) {
        JsonNode node = find(source, path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            states.put(field, notApplicable.contains(field) ? "not_applicable" : "not_reported");
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            evidence.put(path, "invalid_number");
            problems.add("INVALID_TOKEN_VALUE:" + field);
            states.put(field, "not_reported");
            return null;
        }
        long value = node.longValue();
        evidence.put(path, value);
        if (value < 0 || value > MAX_TOKENS) {
            problems.add("TOKEN_VALUE_OUT_OF_RANGE:" + field);
            states.put(field, "not_reported");
            return null;
        }
        states.put(field, "reported");
        return value;
    }

    static JsonNode find(JsonNode source, String path) {
        JsonNode node = source;
        for (String part : path.split("\\.")) {
            if (node != null && node.isArray() && part.equals("AUDIO")) {
                JsonNode found = null;
                for (var item : node) {
                    if (item.path("modality").asText().equals("AUDIO")) {
                        if (found != null) {
                            return JsonNodeFactory.instance.textNode("invalid_number");
                        }
                        found = item.path("tokenCount");
                        if (found.isMissingNode() || found.isNull()) {
                            found = JsonNodeFactory.instance.textNode("invalid_number");
                        }
                    }
                }
                node = found;
            } else {
                node = node == null ? null : node.path(part);
            }
        }
        return node;
    }

    Long effective(String field, Long value) {
        if (value == null && "not_applicable".equals(states.get(field))) {
            return 0L;
        }
        return value;
    }

    Long derive(String field, Long... values) {
        long sum = 0;
        for (Long value : values) {
            if (value == null) {
                states.put(field, "not_reported");
                return null;
            }
            sum += value;
        }
        if (sum < 0 || sum > MAX_TOKENS) {
            problems.add("INCONSISTENT_TOKEN_TOTAL:" + field);
            states.put(field, "not_reported");
            return null;
        }
        states.put(field, "derived");
        return sum;
    }

    void compare(String field, Long actual, Long expected) {
        if (actual != null && expected != null && !actual.equals(expected)) {
            problems.add("INCONSISTENT_TOKEN_TOTAL:" + field);
        }
    }

    void unsupported(String field, String path) {
        Long value = read(field, path);
        if (value != null && value > 0 && !unsupported.contains(field)) {
            unsupported.add(field);
        }
    }
}
