package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;

/**
 * 整数与小数用精确字符串传递，避免浏览器将大整数或小数舍入。
 */
@Component
public class DataValueCodec {
    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final ObjectMapper json = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(10 * 1024 * 1024).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonNode csv(DataField field, String value, long row) {
        try {
            if (value == null) {
                if (!field.nullable()) {
                    throw new IllegalArgumentException();
                }
                return NullNode.getInstance();
            }
            return switch (field.valueType()) {
                case "string" -> TextNode.valueOf(value);
                case "integer" -> {
                    if (!value.matches("-?[0-9]+")) {
                        throw new IllegalArgumentException();
                    }
                    var number = new BigInteger(value);
                    if (number.abs().toString().length() > 65) {
                        throw new IllegalArgumentException();
                    }
                    yield TextNode.valueOf(number.toString());
                }
                case "decimal" -> {
                    BigDecimal number = new BigDecimal(value);
                    if (number.precision() > 65 || number.scale() > 30 || (long) number.precision() - number.scale() > 35) {
                        throw new IllegalArgumentException();
                    }
                    yield TextNode.valueOf(number.stripTrailingZeros().toPlainString());
                }
                case "boolean" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException();
                    }
                    yield BooleanNode.valueOf(Boolean.parseBoolean(value));
                }
                case "date" -> {
                    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                        throw new IllegalArgumentException();
                    }
                    yield TextNode.valueOf(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).toString());
                }
                case "datetime" -> TextNode.valueOf(
                    INSTANT.format(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()));
                case "object" -> {
                    var object = json.readTree(value);
                    if (object == null || !object.isObject()) {
                        throw new IllegalArgumentException();
                    }
                    yield object;
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (Exception invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_VALUE_TYPE_INVALID",
                "数据值与字段类型不符，请检查对应行列。",
                Map.of("row", row, "column", field.ordinal() + 1, "field", field.name(), "expectedType",
                    field.valueType()), Map.of());
        }
    }

    public JsonNode parameter(DataField field, JsonNode value) {
        try {
            return parameterValue(field, value);
        } catch (ApiException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_FILTER_TYPE_INVALID",
                "筛选值与字段类型不符，请检查筛选条件。",
                Map.of("field", field.name(), "expectedType", field.valueType()), Map.of());
        }
    }

    private JsonNode parameterValue(DataField field, JsonNode value) {
        if (value == null || value.isNull()) {
            return csv(field, null, 0);
        }
        if (field.valueType().equals("object")) {
            if (!value.isObject()) {
                throw ApiException.invalidField("filters", "JSON 对象字段的比较值必须是对象。");
            }
            return csv(field, value.toString(), 0);
        }
        if (value.isContainerNode() || (field.valueType().equals("string") && !value.isTextual())) {
            throw ApiException.invalidField("filters", "筛选值与字段类型不一致。");
        }
        return csv(field, value.asText(), 0);
    }
}
