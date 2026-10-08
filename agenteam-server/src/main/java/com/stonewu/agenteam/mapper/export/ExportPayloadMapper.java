package com.stonewu.agenteam.mapper.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.export.entity.ExportPayload;
import org.springframework.stereotype.Component;

/**
 * 内部导出条件与文件摘要单独转换，不把完整任务记录序列化给客户端。
 */
@Component
public class ExportPayloadMapper {
    private final ObjectMapper json;

    public ExportPayloadMapper(ObjectMapper json) {
        this.json = json;
    }

    public ExportPayload read(String value) {
        try {
            var root = json.readTree(value);
            var definition = root.path("definition");
            if (definition instanceof ObjectNode original && !original.has("parameters")) {
                // 旧版本将各类型的条件直接放在 definition 下；读取时保留原字段名和值。
                var parameters = json.createObjectNode();
                original.properties().forEach(entry -> {
                    if (!entry.getKey().equals("type") && !entry.getValue().isNull()) {
                        parameters.set(entry.getKey(), entry.getValue());
                    }
                });
                var converted = json.createObjectNode();
                converted.set("type", original.get("type"));
                converted.set("parameters", parameters);
                ((ObjectNode) root).set("definition", converted);
            }
            return json.treeToValue(root, ExportPayload.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("导出任务的内部条件无法读取", invalid);
        }
    }

    public String write(ExportPayload value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("导出任务的内部条件无法保存", invalid);
        }
    }
}
