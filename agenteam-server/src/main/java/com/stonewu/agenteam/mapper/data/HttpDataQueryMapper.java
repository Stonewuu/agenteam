package com.stonewu.agenteam.mapper.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.model.data.entity.DataQueryRows;
import com.stonewu.agenteam.service.data.http.HttpDataSourceReader;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 在一次受限响应内完成已授权字段的筛选和排序，不能向接口提交查询语句或下一页地址。
 */
@Component
public class HttpDataQueryMapper {
    private final HttpDataSourceReader reader;
    private final HttpDataResponseMapper mapping;
    private final ObjectMapper json;

    public HttpDataQueryMapper(HttpDataSourceReader reader, HttpDataResponseMapper mapping, ObjectMapper json) {
        this.reader = reader;
        this.mapping = mapping;
        this.json = json;
    }

    public DataQueryRows query(String enterprise, JsonNode config, DataQueryPlan plan, long deadline, int byteBudget) {
        try (var control = new ToolCallControl(() -> {
        })) {
            return query(enterprise, config, plan, deadline, byteBudget, control);
        }
    }

    public DataQueryRows query(String enterprise, JsonNode config, DataQueryPlan plan, long deadline, int byteBudget,
                               ToolCallControl control) {
        var required = new LinkedHashMap<String, DataField>();
        plan.fields().forEach(field -> required.put(field.name(), field));
        plan.conditions().forEach(condition -> required.put(condition.field().name(), condition.field()));
        plan.order().forEach(order -> required.put(order.field().name(), order.field()));
        var source = reader.read(enterprise, config, plan.conditions(), deadline, control);
        var candidates = mapping.rows(source, plan.collection().sourceName(), List.copyOf(required.values()), deadline);
        candidates.removeIf(row -> {
            HttpDataSourceReader.remaining(deadline);
            return plan.conditions().stream()
                .anyMatch(condition -> !matches(row.get(condition.field().name()), condition));
        });
        if (!plan.order().isEmpty()) {
            candidates.sort((left, right) -> {
                HttpDataSourceReader.remaining(deadline);
                for (var order : plan.order()) {
                    int compared = compare(left.get(order.field().name()), right.get(order.field().name()),
                        order.field());
                    if (compared != 0) {
                        return order.direction().equals("asc") ? compared : -compared;
                    }
                }
                return 0;
            });
        }
        List<ObjectNode> result = new ArrayList<>();
        int used = 2;
        boolean truncated = false;
        for (int index = plan.offset(); index < candidates.size() && result.size() < plan.limit(); index++) {
            HttpDataSourceReader.remaining(deadline);
            ObjectNode row = json.createObjectNode();
            var candidate = candidates.get(index);
            plan.fields().forEach(field -> row.set(field.name(), candidate.get(field.name())));
            int bytes = bytes(row) + 1;
            if (used + bytes > byteBudget) {
                truncated = true;
                break;
            }
            result.add(row);
            used += bytes;
        }
        return new DataQueryRows(List.copyOf(result),
            truncated || (long) plan.offset() + result.size() < candidates.size(), truncated);
    }

    private boolean matches(JsonNode actual, DataQueryPlan.Condition condition) {
        JsonNode value = condition.value();
        String op = condition.operator();
        if (op.equals("is_null")) {
            return actual.isNull() == (value == null || value.isNull() || value.asBoolean());
        }
        if (actual.isNull()) {
            return false;
        }
        if (op.equals("contains")) {
            return actual.asText().contains(value.asText());
        }
        if (op.equals("in")) {
            for (var option : value) {
                if (equal(actual, option, condition.field())) {
                    return true;
                }
            }
            return false;
        }
        if (op.equals("eq")) {
            return equal(actual, value, condition.field());
        }
        if (op.equals("ne")) {
            return !equal(actual, value, condition.field());
        }
        int compared = compare(actual, value, condition.field());
        return switch (op) {
            case "gt" -> compared > 0;
            case "gte" -> compared >= 0;
            case "lt" -> compared < 0;
            case "lte" -> compared <= 0;
            default -> throw new IllegalStateException("数据比较条件未经验证");
        };
    }

    private boolean equal(JsonNode left, JsonNode right, DataField field) {
        return field.valueType().equals("object") ? left.equals(right) : compare(left, right, field) == 0;
    }

    private int compare(JsonNode left, JsonNode right, DataField field) {
        if (left.isNull() || right.isNull()) {
            return left.isNull() ? right.isNull() ? 0 : -1 : 1;
        }
        return switch (field.valueType()) {
            case "integer", "decimal" -> new BigDecimal(left.asText()).compareTo(new BigDecimal(right.asText()));
            case "boolean" -> Boolean.compare(left.asBoolean(), right.asBoolean());
            default -> Arrays.compareUnsigned(left.asText().getBytes(StandardCharsets.UTF_8),
                right.asText().getBytes(StandardCharsets.UTF_8));
        };
    }

    private int bytes(ObjectNode row) {
        try {
            return json.writeValueAsBytes(row).length;
        } catch (Exception invalid) {
            throw new IllegalStateException("数据行无法编码", invalid);
        }
    }
}
