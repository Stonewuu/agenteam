package com.stonewu.agenteam.model.data.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 字段和比较值已经逐项验证，查询适配器不再接受任意字段或语句。
 */
public record DataQueryPlan(DataCollectionRecord collection, int generation, List<DataField> fields,
                            List<Condition> conditions,
                            List<Order> order, int limit, int offset) {
    public record Condition(DataField field, String operator, JsonNode value) {
    }

    public record Order(DataField field, String direction) {
    }
}
