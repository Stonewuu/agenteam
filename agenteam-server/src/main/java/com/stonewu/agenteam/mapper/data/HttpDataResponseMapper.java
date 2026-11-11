package com.stonewu.agenteam.mapper.data;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.service.data.DataValueCodec;
import com.stonewu.agenteam.service.data.http.HttpDataSourceReader;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 集合保存数组位置，字段保存顶层名称或 JSON Pointer（JSON 内容中的明确路径），不执行表达式。
 */
@Component
public class HttpDataResponseMapper {
    private final ObjectMapper json;
    private final DataValueCodec values;

    public HttpDataResponseMapper(ObjectMapper json, DataValueCodec values) {
        this.json = json;
        this.values = values;
    }

    public List<ObjectNode> rows(JsonNode response, String sourceName, List<DataField> fields, long deadline) {
        if (sourceName == null || !(sourceName.equals("$") || sourceName.startsWith("/"))) {
            throw ApiException.invalidField("sourceName", Messages.text("error.data.httpDataResponseMapper.rows.1"));
        }
        JsonNode source = sourceName.equals("$") ? response : response.at(pointer(sourceName));
        if (!source.isArray()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_HTTP_ARRAY_UNAVAILABLE",
                Messages.text("error.data.httpDataResponseMapper.rows.2"));
        }
        if (source.size() > 100000) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_HTTP_ROW_LIMIT",
                Messages.text("error.data.httpDataResponseMapper.rows.3"));
        }
        fields.stream().filter(field -> field.name().startsWith("/")).forEach(field -> pointer(field.name()));
        List<ObjectNode> result = new ArrayList<>();
        long row = 0;
        for (var raw : source) {
            HttpDataSourceReader.remaining(deadline);
            row++;
            if (!raw.isObject()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_HTTP_ROW_INVALID",
                    Messages.text("error.data.httpDataResponseMapper.rows.4"));
            }
            ObjectNode mapped = json.createObjectNode();
            for (var field : fields) {
                JsonNode value = field.name().startsWith("/") ? raw.at(pointer(field.name())) : raw.path(field.name());
                if (value.isMissingNode()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_SOURCE_FIELD_MISMATCH",
                        Messages.text("error.data.httpDataResponseMapper.rows.5"), Map.of("field", field.name(), "row", row), Map.of());
                }
                if (!value.isNull() && (field.valueType()
                    .equals("object") ? !value.isObject() : value.isContainerNode())) {
                    throw ApiException.invalidField("fields", Messages.text("error.data.httpDataResponseMapper.rows.6"));
                }
                mapped.set(field.name(), values.csv(field,
                    value.isNull() ? null : value.isContainerNode() ? value.toString() : value.asText(), row));
            }
            result.add(mapped);
        }
        return result;
    }

    private JsonPointer pointer(String value) {
        if (value.matches(".*~(?:[^01]|$).*")) {
            throw ApiException.invalidField("sourceName", Messages.text("error.data.httpDataResponseMapper.pointer.1"));
        }
        try {
            return JsonPointer.compile(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidField("sourceName", Messages.text("error.data.httpDataResponseMapper.pointer.2"));
        }
    }
}
