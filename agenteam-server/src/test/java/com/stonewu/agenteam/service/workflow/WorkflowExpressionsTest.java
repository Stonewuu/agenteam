package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowExpressionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowExpressions expressions = new WorkflowExpressions(json);
    private final WorkflowConditions conditions = new WorkflowConditions(expressions);

    @Test
    void aWholeVariableKeepsObjectArrayBooleanNullAndExactNumberTypes() throws Exception {
        var input = (ObjectNode) json.readTree("{\"record\":{\"name\":\"活动\"},\"items\":[3,4],\"ready\":true,\"nothing\":null,\"id\":9007199254740993}");
        input.put("amount", new BigDecimal("0.100000000000000000000000000001"));
        var mapping = json.valueToTree(Map.of("record", "${input.record}", "items", "${input.items}", "ready", "${input.ready}", "nothing", "${input.nothing}", "id", "${input.id}", "amount", "${input.amount}"));
        var result = expressions.resolve(mapping, input, Map.of());
        assertTrue(result.path("record").isObject());
        assertTrue(result.path("items").isArray());
        assertTrue(result.path("ready").isBoolean());
        assertTrue(result.path("nothing").isNull());
        assertEquals("9007199254740993", result.path("id").asText());
        assertEquals(input.path("amount").decimalValue(), result.path("amount").decimalValue());
        ((ObjectNode) result.path("record")).put("name", "修改副本");
        assertEquals("活动", input.at("/record/name").asText());
    }

    @Test
    void combinesOnlyScalarsAndLeavesExplicitLiteralsUntouched() {
        var input = json.valueToTree(Map.of("name", "活动", "count", 2, "ready", true, "record", Map.of("a", 1)));
        assertEquals("活动：2 / true", expressions.resolve(json.getNodeFactory().textNode("${input.name}：${input.count} / ${input.ready}"), input, Map.of()).asText());
        assertEquals("WORKFLOW_TEMPLATE_TYPE_INVALID", assertThrows(ApiException.class, () -> expressions.resolve(json.getNodeFactory().textNode("结果：${input.record}"), input, Map.of())).code());
        var fields = json.valueToTree(List.of(Map.of("target", "name", "source", "input.name"), Map.of("target", "title", "template", "准备${input.count}项"), Map.of("target", "example", "literal", "${input.name}")));
        var result = expressions.transform(fields, input, Map.of());
        assertEquals("准备2项", result.path("title").asText());
        assertEquals("${input.name}", result.path("example").asText());
    }

    @Test
    void missingOutputsStayMissingAndArrayPositionsDoNotRunExpressions() {
        var input = json.valueToTree(Map.of("items", List.of(Map.of("name", "第一项"))));
        assertEquals("第一项", expressions.read("input.items.0.name", input, Map.of()).asText());
        assertTrue(expressions.read("steps.skipped.output", input, Map.of()).isMissingNode());
        assertEquals("WORKFLOW_INPUT_MISSING", assertThrows(ApiException.class, () -> expressions.resolve(json.getNodeFactory().textNode("${input.items.8.name}"), input, Map.of())).code());
        assertThrows(ApiException.class, () -> expressions.validateSelector("input['name']", Set.of()));
        assertThrows(ApiException.class, () -> expressions.validateTemplates(json.getNodeFactory().textNode("${input.${input.name}}"), Set.of()));
    }

    @Test
    void comparisonDoesNotCoerceStringsAndNumericEqualityIgnoresDecimalScale() throws Exception {
        var input = (ObjectNode) json.readTree("{\"number\":9007199254740993,\"text\":\"9007199254740993\"}");
        input.put("amount", new BigDecimal("0.10"));
        assertFalse(test(input, "input.number", "eq", "9007199254740993"));
        assertTrue(test(input, "input.number", "lt", 9007199254740994L));
        assertTrue(test(input, "input.amount", "eq", new BigDecimal("0.1")));
        assertEquals("WORKFLOW_COMPARISON_INVALID", assertThrows(ApiException.class, () -> test(input, "input.number", "gt", "1")).code());
        assertThrows(ApiException.class, () -> test(input, "input.text", "in", "9007199254740993"));
        assertTrue(test(input, "input.text", "in", List.of("9007199254740993")));
    }

    @Test
    void existsDistinguishesNullFromMissingAndAllowsSafeShortCircuiting() throws Exception {
        var input = json.readTree("{\"nothing\":null}");
        assertTrue(conditions.evaluate(json.valueToTree(Map.of("field", "input.nothing", "operator", "exists")), input, Map.of()));
        var exists = json.valueToTree(Map.of("field", "input.absent", "operator", "exists"));
        var comparison = json.valueToTree(Map.of("field", "input.absent", "operator", "gt", "value", 1));
        assertFalse(conditions.evaluate(json.valueToTree(Map.of("all", List.of(exists, comparison))), input, Map.of()));
        assertEquals("WORKFLOW_INPUT_MISSING", assertThrows(ApiException.class, () -> conditions.evaluate(comparison, input, Map.of())).code());
    }

    @Test
    void repeatedMappingCannotExpandOneInputBeyondTheOutputLimit() {
        var input = json.valueToTree(Map.of("text", "内容".repeat(150000)));
        var mapping = json.valueToTree(Map.of("first", "${input.text}", "second", "${input.text}"));
        assertEquals("WORKFLOW_OUTPUT_TOO_LARGE", assertThrows(ApiException.class, () -> expressions.resolve(mapping, input, Map.of())).code());
    }

    private boolean test(JsonNode input, String field, String operator, Object value) {
        return conditions.evaluate(json.valueToTree(Map.of("field", field, "operator", operator, "value", value)), input, Map.of());
    }
}
