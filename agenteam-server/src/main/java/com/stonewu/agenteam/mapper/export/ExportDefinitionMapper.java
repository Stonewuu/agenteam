package com.stonewu.agenteam.mapper.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 各导出处理器使用明确的请求类型解释条件，公共任务不引用扩展的查询模型。 */
@Component
public class ExportDefinitionMapper {
    private final ObjectMapper json;

    public ExportDefinitionMapper(ObjectMapper json) {
        this.json = json;
    }

    public ExportDefinition create(String type, String parameter, Object value) {
        return new ExportDefinition(type, Map.of(parameter, json.valueToTree(value)));
    }

    public <T> T parameter(ExportDefinition definition, String name, Class<T> type) {
        var value = definition.parameters().get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return json.treeToValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw ApiException.invalidField(name, "导出条件无效，请重新选择后导出。", invalid);
        }
    }
}
