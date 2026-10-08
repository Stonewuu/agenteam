package com.stonewu.agenteam.model.export.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仅由已校验的接口构造，后台任务不能指定任意查询或文件路径。
 */
public record ExportDefinition(String type, Map<String, JsonNode> parameters) {
    public ExportDefinition {
        var copied = new LinkedHashMap<String, JsonNode>();
        if (parameters != null) {
            parameters.forEach((key, value) -> copied.put(key, value.deepCopy()));
        }
        parameters = Map.copyOf(copied);
    }
}
