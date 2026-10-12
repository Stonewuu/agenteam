package com.stonewu.examples.jsontools;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.plugin.api.JavaPluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 以对象字段和数组位置比较，达到数量或结果长度限制时明确返回未展示标记。
 */
final class JsonDifferences {
    private final JsonDocuments documents;
    private final JavaPluginContext context;
    private final int limit;
    private final List<Map<String, Object>> differences = new ArrayList<>();
    private boolean truncated;
    private int resultLength;

    JsonDifferences(JsonDocuments documents, JavaPluginContext context, int limit) {
        this.documents = documents;
        this.context = context;
        this.limit = limit;
    }

    Map<String, Object> compare(JsonNode left, JsonNode right) {
        visit("", left, right);
        boolean same = differences.isEmpty() && !truncated;
        String content = same ? "两份 JSON 的结构与数值相同。" : "已显示 " + differences.size() + " 处差异。"
            + (truncated ? "仍有差异未显示，请提高差异数量上限或提取局部后比较。" : "");
        return Map.of("content", content, "isError", false, "same", same, "differences", differences,
            "differenceCount", differences.size(), "truncated", truncated);
    }

    private void visit(String path, JsonNode left, JsonNode right) {
        context.cancellation().requireActive();
        if (truncated) {
            return;
        }
        if (left == null || right == null) {
            add(path, left, right);
        } else if (left.isNumber() && right.isNumber()) {
            if (left.decimalValue().compareTo(right.decimalValue()) != 0) {
                add(path, left, right);
            }
        } else if (left.isObject() && right.isObject()) {
            var fields = new TreeSet<String>();
            left.fieldNames().forEachRemaining(fields::add);
            right.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                visit(path + "/" + field.replace("~", "~0").replace("/", "~1"), left.get(field), right.get(field));
                if (truncated) {
                    break;
                }
            }
        } else if (left.isArray() && right.isArray()) {
            for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
                visit(path + "/" + index, left.get(index), right.get(index));
                if (truncated) {
                    break;
                }
            }
        } else if (!left.equals(right)) {
            add(path, left, right);
        }
    }

    private void add(String path, JsonNode left, JsonNode right) {
        if (differences.size() >= limit) {
            truncated = true;
            return;
        }
        var difference = new LinkedHashMap<String, Object>();
        difference.put("path", path);
        difference.put("type", left == null ? "added" : right == null ? "removed" : "changed");
        int length = path.length();
        if (left != null) {
            String before = documents.render(left, false, 2);
            difference.put("before", before);
            length += before.length();
        }
        if (right != null) {
            String after = documents.render(right, false, 2);
            difference.put("after", after);
            length += after.length();
        }
        if (resultLength + length > JsonDocuments.MAX_RESULT_LENGTH) {
            truncated = true;
            return;
        }
        resultLength += length;
        differences.add(difference);
    }
}
