package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据查询只传递字段定义、有限的比较方式和绑定值，不传递完整语句。
 */
public record DataQueryParameters(DataQueryPlan plan, String database, String table, List<Field> fields,
                                  List<Comparison> conditions, List<Order> order) {
    public record Field(DataField definition, String identifier) {
        public Field(DataField definition) {
            this(definition, quoteIdentifier(definition.name()));
        }
    }

    public record Comparison(Field field, String operator, Object value, List<Object> values, boolean isNull) {
    }

    public record Order(Field field, boolean ascending) {
    }

    public static DataQueryParameters prepare(DataQueryPlan plan, String database) {
        var conditions = new ArrayList<Comparison>();
        for (var condition : plan.conditions()) {
            String operator = condition.operator();
            if (!Set.of("eq", "ne", "gt", "gte", "lt", "lte", "is_null", "contains", "in").contains(operator)) {
                throw new IllegalArgumentException("未验证的比较条件");
            }
            var value = condition.value();
            var parameters = new ArrayList<Object>();
            if (operator.equals("in")) {
                value.forEach(item -> parameters.add(parameter(condition.field(), item)));
            }
            Object bound = Set.of("is_null", "in").contains(operator) ? null
                : operator.equals("contains") ? value.asText() : parameter(condition.field(), value);
            conditions.add(new Comparison(new Field(condition.field()), operator, bound, List.copyOf(parameters),
                value == null || value.isNull() || value.asBoolean()));
        }
        var order = plan.order().stream().map(item -> {
            if (!Set.of("asc", "desc").contains(item.direction())) {
                throw new IllegalArgumentException("未验证的排序方向");
            }
            return new Order(new Field(item.field()), item.direction().equals("asc"));
        }).toList();
        return new DataQueryParameters(plan, database == null ? null : quoteIdentifier(database),
            database == null ? null : quoteIdentifier(plan.collection().sourceName()),
            plan.fields().stream().map(Field::new).toList(), List.copyOf(conditions), order);
    }

    private static Object parameter(DataField field, JsonNode value) {
        return switch (field.valueType()) {
            case "integer", "decimal" -> new BigDecimal(value.asText());
            case "boolean" -> value.asBoolean() ? 1 : 0;
            case "object" -> value.toString();
            default -> value.asText();
        };
    }

    /**
     * 标识符不支持绑定占位符；每个名称单独引用，反引号只能作为名称内容。
     */
    public static String quoteIdentifier(String value) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("数据库名称或字段名称无效");
        }
        return "`" + value.replace("`", "``") + "`";
    }
}
