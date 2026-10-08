package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 节点结果有独立大小限制；恢复时按十进制读取数字，避免再转成浮点数。
 */
@Component
public class WorkflowValues {
    private final ObjectMapper json;
    private final ObjectReader reader;

    public WorkflowValues(ObjectMapper json) {
        this.json = json;
        reader = json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public String write(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("工作流结果不能为空引用或缺失字段");
        }
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > WorkflowExpressions.MAX_VALUE_BYTES) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "WORKFLOW_OUTPUT_TOO_LARGE", "节点结果超过 1,048,576 字节，请减少返回内容或使用附件。");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (ApiException known) {
            throw known;
        } catch (Exception invalid) {
            throw new IllegalStateException("工作流结果无法保存", invalid);
        }
    }

    public JsonNode read(String value) {
        try {
            return value == null ? null : reader.readValue(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("已保存的工作流内容无法读取", invalid);
        }
    }

    public JsonNode error(String code, String message) {
        var value = json.createObjectNode();
        value.put("isError", true);
        value.putObject("error").put("code", code).put("message", message);
        return value;
    }
}
