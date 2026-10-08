package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 变量只沿输入或已完成输出读取，不调用脚本、反射、文件或网络。
 */
@Component
public class WorkflowExpressions {
    private static final Pattern INPUT = Pattern.compile("input(?:\\.[^\\s.{}]+)*");
    private static final Pattern STEP = Pattern.compile("steps\\.([A-Za-z][A-Za-z0-9_-]*)\\.output(?:\\.[^\\s.{}]+)*");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^{}]+)}");
    public static final int MAX_VALUE_BYTES = 1024 * 1024;
    private final ObjectMapper json;

    public WorkflowExpressions(ObjectMapper json) {
        this.json = json;
    }

    public void validateSelector(String value, Set<String> previous) {
        if (value == null || value.length() > 200) {
            throw syntax("变量路径不能超过二百个字符。");
        }
        if (INPUT.matcher(value).matches()) {
            return;
        }
        var step = STEP.matcher(value);
        if (!step.matches() || !previous.contains(step.group(1))) {
            throw syntax("变量只能引用输入或已经位于当前节点之前的步骤结果。");
        }
    }

    public void validateTemplates(JsonNode value, Set<String> previous) {
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            String text = value.asText();
            var matcher = TEMPLATE.matcher(text);
            int end = 0;
            while (matcher.find()) {
                if (text.substring(end, matcher.start()).contains("${")) {
                    throw syntax("变量表达式没有完整闭合，请检查 ${ 与 }。");
                }
                validateSelector(matcher.group(1), previous);
                end = matcher.end();
            }
            if (text.substring(end).contains("${")) {
                throw syntax("变量表达式没有完整闭合，请检查 ${ 与 }。");
            }
        } else if (value.isContainerNode()) {
            value.forEach(child -> validateTemplates(child, previous));
        }
    }

    public JsonNode read(String selector, JsonNode input, Map<String, JsonNode> outputs) {
        if (selector == null || selector.length() > 200) {
            throw syntax("变量路径不符合要求。");
        }
        JsonNode current;
        String suffix;
        if (INPUT.matcher(selector).matches()) {
            current = input;
            suffix = selector.substring(5);
        } else {
            var step = STEP.matcher(selector);
            if (!step.matches()) {
                throw syntax("变量路径不符合要求。");
            }
            current = outputs.get(step.group(1));
            suffix = selector.substring(("steps." + step.group(1) + ".output").length());
        }
        if (current == null) {
            return MissingNode.getInstance();
        }
        if (suffix.isEmpty()) {
            return current;
        }
        for (String key : suffix.substring(1).split("\\.", -1)) {
            if (current.isObject()) {
                current = current.path(key);
            } else if (current.isArray() && key.matches("0|[1-9][0-9]{0,8}")) {
                current = current.path(Integer.parseInt(key));
            } else {
                return MissingNode.getInstance();
            }
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    public JsonNode resolve(JsonNode mapping, JsonNode input, Map<String, JsonNode> outputs) {
        return resolve(mapping, input, outputs, new Budget(), 0);
    }

    public JsonNode transform(JsonNode fields, JsonNode input, Map<String, JsonNode> outputs) {
        var result = json.createObjectNode();
        var budget = new Budget();
        budget.add(2);
        for (var field : fields) {
            String target = field.path("target").asText();
            budget.add(encoded(target).length + 2);
            JsonNode value;
            if (field.has("source")) {
                value = required(field.path("source").asText(), input, outputs);
            } else if (field.has("template")) {
                value = text(field.path("template").asText(), input, outputs);
            } else {
                value = field.get("literal");
            }
            budget.add(encoded(value).length);
            result.set(target, value.deepCopy());
        }
        return result;
    }

    private JsonNode resolve(JsonNode value, JsonNode input, Map<String, JsonNode> outputs, Budget budget, int depth) {
        if (depth > 64 || value == null) {
            throw syntax("变量映射层级过深或内容不完整。");
        }
        if (value.isObject()) {
            var result = json.createObjectNode();
            budget.add(2);
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                budget.add(encoded(field.getKey()).length + 2);
                result.set(field.getKey(), resolve(field.getValue(), input, outputs, budget, depth + 1));
            }
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            budget.add(2);
            for (var child : value) {
                budget.add(1);
                result.add(resolve(child, input, outputs, budget, depth + 1));
            }
            return result;
        }
        JsonNode result = value.isTextual() ? text(value.asText(), input, outputs) : value;
        budget.add(encoded(result).length);
        return result.deepCopy();
    }

    private JsonNode text(String source, JsonNode input, Map<String, JsonNode> outputs) {
        var matcher = TEMPLATE.matcher(source);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            String literal = source.substring(end, matcher.start());
            if (literal.contains("${")) {
                throw syntax("变量表达式没有完整闭合。");
            }
            JsonNode value = required(matcher.group(1), input, outputs);
            if (matcher.start() == 0 && matcher.end() == source.length()) {
                return value;
            }
            if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
                throw failure("WORKFLOW_TEMPLATE_TYPE_INVALID",
                    "组合文字只能使用文字、数字或布尔值（真或假），不能直接拼接对象、数组或空值。");
            }
            append(result, literal);
            append(result, value.asText());
            end = matcher.end();
        }
        String tail = source.substring(end);
        if (tail.contains("${")) {
            throw syntax("变量表达式没有完整闭合。");
        }
        append(result, tail);
        return json.getNodeFactory().textNode(result.toString());
    }

    private JsonNode required(String selector, JsonNode input, Map<String, JsonNode> outputs) {
        var value = read(selector, input, outputs);
        if (value.isMissingNode()) {
            throw failure("WORKFLOW_INPUT_MISSING", "需要的输入字段或已完成步骤结果不存在，请检查节点映射。");
        }
        return value;
    }

    private void append(StringBuilder text, String value) {
        if ((long) text.length() + value.length() > MAX_VALUE_BYTES) {
            throw tooLarge();
        }
        text.append(value);
    }

    private byte[] encoded(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception invalid) {
            throw failure("WORKFLOW_VALUE_INVALID", "节点内容无法保存为结构化数据。");
        }
    }

    public static ApiException syntax(String message) {
        return ApiException.invalidField("config.nodes", message);
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static ApiException tooLarge() {
        return failure("WORKFLOW_OUTPUT_TOO_LARGE", "节点结果超过 1,048,576 字节，请减少返回内容或使用附件。");
    }

    private static final class Budget {
        private long bytes;

        void add(long size) {
            bytes += size;
            if (bytes > MAX_VALUE_BYTES) {
                throw tooLarge();
            }
        }
    }
}
