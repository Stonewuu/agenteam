package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 旧版本最多保留原有可读范围，当前撤销的字段、筛选和排序不能继续使用。
 */
@Component
public class DataQueryValidation {
    private final DataValueCodec values;
    private final ObjectMapper json;

    public DataQueryValidation(DataValueCodec values, ObjectMapper json) {
        this.values = values;
        this.json = json;
    }

    public DataQueryPlan plan(DataCollectionRecord collection, List<DataField> original, List<DataField> current,
                              DataQueryRequest input) {
        if (!collection.status().equals(
            "active") || input.generation() < 1 || input.generation() > collection.activeGeneration() || original.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_UNAVAILABLE",
                Messages.text("error.data.dataQueryService.plan.2"));
        }
        if (input.fields() == null || input.fields().isEmpty() || input.fields().size() > 100 || new HashSet<>(
            input.fields()).size() != input.fields().size()) {
            throw ApiException.invalidField("fields", Messages.text("error.data.dataQueryValidation.plan.1"));
        }
        int limit = input.limit() == null ? 50 : input.limit(), offset = input.offset() == null ? 0 : input.offset();
        if (limit < 1 || limit > 200 || offset < 0 || offset > 100000) {
            throw ApiException.invalidField("limit", Messages.text("error.data.dataQueryValidation.plan.2"));
        }
        if (input.filters() == null || input.filters().size() > 20 || input.sort() == null || input.sort().size() > 3) {
            throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.3"));
        }
        var currentByName = new HashMap<String, DataField>();
        current.forEach(field -> currentByName.put(field.name(), field));
        Map<String, DataField> permitted = new HashMap<>();
        for (var field : original) {
            var latest = currentByName.get(field.name());
            if (latest != null && latest.readable() && field.readable() && latest.valueType()
                .equals(field.valueType())) {
                permitted.put(field.name(), new DataField(field.name(), latest.label(), field.valueType(), true,
                    field.filterable() && latest.filterable(),
                    field.sortable() && latest.sortable(), field.sensitive() || latest.sensitive(), field.nullable(),
                    field.ordinal()));
            }
        }
        var selected = input.fields().stream().map(name -> field(permitted, name)).toList();
        var conditions = new ArrayList<DataQueryPlan.Condition>();
        for (var filter : input.filters()) {
            var field = field(permitted, filter.field());
            if (!field.filterable()) {
                throw unavailable();
            }
            String operator = filter.operator();
            if (operator == null || !Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "contains", "is_null")
                .contains(operator)) {
                throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.4"));
            }
            JsonNode value = filter.value() instanceof JsonNode node ? node : json.valueToTree(filter.value());
            if (operator.equals("is_null")) {
                if (value != null && !value.isNull() && !value.isBoolean()) {
                    throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.5"));
                }
            } else if (operator.equals("in")) {
                if (value == null || !value.isArray() || value.isEmpty() || value.size() > 100) {
                    throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.6"));
                }
                var checked = json.createArrayNode();
                for (var item : value) {
                    if (item.isNull()) {
                        throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.7"));
                    }
                    checked.add(values.parameter(field, item));
                }
                value = checked;
            } else {
                if (value == null || value.isNull()) {
                    throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.7"));
                }
                value = values.parameter(field, value);
                if (operator.equals("contains") && !field.valueType().equals("string")) {
                    throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.8"));
                }
                if (Set.of("gt", "gte", "lt", "lte").contains(operator) && Set.of("boolean", "object")
                    .contains(field.valueType())) {
                    throw ApiException.invalidField("filters", Messages.text("error.data.dataQueryValidation.plan.9"));
                }
            }
            conditions.add(new DataQueryPlan.Condition(field, operator, value));
        }
        var order = new ArrayList<DataQueryPlan.Order>();
        var sorted = new HashSet<String>();
        for (var item : input.sort()) {
            var field = field(permitted, item.field());
            if (!field.sortable() || field.valueType().equals("object")) {
                throw unavailable();
            }
            if (!sorted.add(field.name()) || item.direction() == null || !Set.of("asc", "desc")
                .contains(item.direction())) {
                throw ApiException.invalidField("sort", Messages.text("error.data.dataQueryValidation.plan.10"));
            }
            order.add(new DataQueryPlan.Order(field, item.direction()));
        }
        return new DataQueryPlan(collection, input.generation(), selected, List.copyOf(conditions), List.copyOf(order),
            limit, offset);
    }

    private DataField field(Map<String, DataField> fields, String name) {
        var field = fields.get(name);
        if (field == null) {
            throw unavailable();
        }
        return field;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "DATA_FIELD_UNAVAILABLE",
            Messages.text("error.data.dataQueryValidation.unavailable.1"));
    }
}
