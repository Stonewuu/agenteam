package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 条件保留值类型，缺少字段只能由 exists 判断，不把字符串转换成数字。
 */
@Component
public class WorkflowConditions {
    private final WorkflowExpressions expressions;

    public WorkflowConditions(WorkflowExpressions expressions) {
        this.expressions = expressions;
    }

    public void validate(JsonNode condition, Set<String> previous) {
        validate(condition, previous, 1, new int[]{0});
    }

    private void validate(JsonNode condition, Set<String> previous, int depth, int[] count) {
        if (depth > 5) {
            throw WorkflowExpressions.syntax("条件最多嵌套五层。");
        }
        if (condition.has("field")) {
            if (++count[0] > 50) {
                throw WorkflowExpressions.syntax("比较条件不能超过五十个。");
            }
            expressions.validateSelector(condition.path("field").asText(), previous);
            expressions.validateTemplates(condition.get("value"), previous);
        } else if (condition.has("not")) {
            validate(condition.get("not"), previous, depth + 1, count);
        } else {
            for (var child : condition.has("all") ? condition.get("all") : condition.path("any")) {
                validate(child, previous, depth + 1, count);
            }
        }
    }

    public boolean evaluate(JsonNode condition, JsonNode input, Map<String, JsonNode> outputs) {
        if (condition.has("all")) {
            for (var child : condition.get("all")) {
                if (!evaluate(child, input, outputs)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.has("any")) {
            for (var child : condition.get("any")) {
                if (evaluate(child, input, outputs)) {
                    return true;
                }
            }
            return false;
        }
        if (condition.has("not")) {
            return !evaluate(condition.get("not"), input, outputs);
        }
        String operator = condition.path("operator").asText();
        var left = expressions.read(condition.path("field").asText(), input, outputs);
        if (operator.equals("exists")) {
            return !left.isMissingNode();
        }
        if (left.isMissingNode()) {
            throw failure("WORKFLOW_INPUT_MISSING", "条件需要的输入或步骤结果不存在，请先判断字段是否存在。");
        }
        var right = expressions.resolve(condition.path("value"), input, outputs);
        return switch (operator) {
            case "eq" -> equal(left, right);
            case "ne" -> !equal(left, right);
            case "gt" -> compare(left, right) > 0;
            case "gte" -> compare(left, right) >= 0;
            case "lt" -> compare(left, right) < 0;
            case "lte" -> compare(left, right) <= 0;
            case "in" -> {
                if (!right.isArray()) {
                    throw failure("WORKFLOW_COMPARISON_INVALID", "在这些值中判断需要提供数组。");
                }
                yield contains(right, left);
            }
            case "contains" -> contains(left, right);
            default -> throw failure("WORKFLOW_CONDITION_INVALID", "条件使用了不支持的比较方式。");
        };
    }

    private int compare(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        if (left.isTextual() && right.isTextual()) {
            return left.asText().compareTo(right.asText());
        }
        throw failure("WORKFLOW_COMPARISON_INVALID", "大小比较必须使用两个数字或两段文字。");
    }

    private boolean contains(JsonNode container, JsonNode value) {
        if (container.isTextual() && value.isTextual()) {
            return container.asText().contains(value.asText());
        }
        if (container.isArray()) {
            for (var item : container) {
                if (equal(item, value)) {
                    return true;
                }
            }
            return false;
        }
        throw failure("WORKFLOW_COMPARISON_INVALID", "包含判断需要文字或数组，并使用相符的值类型。");
    }

    private boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!equal(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            var fields = left.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!right.has(field.getKey()) || !equal(field.getValue(), right.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
