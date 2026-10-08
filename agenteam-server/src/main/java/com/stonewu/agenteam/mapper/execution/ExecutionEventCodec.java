package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.stereotype.Component;

/**
 * 原格式数据库行与 Redis 使用原事件摘要；合并记录先还原再按此格式分发。
 */
@Component
public class ExecutionEventCodec {
    private final ObjectMapper json;
    private final ResourceJson canonical;

    public ExecutionEventCodec(ObjectMapper json, ResourceJson canonical) {
        this.json = json;
        this.canonical = canonical;
    }

    public record Stored(String data, String hash) {
    }

    public Stored encode(ExecutionEvent event) {
        var value = json.valueToTree(event);
        return new Stored(canonical.write(value), canonical.hash(value));
    }

    public ExecutionEvent decode(String value, String hash) {
        try {
            var node = json.readTree(value);
            if (hash == null || !hash.equals(canonical.hash(node))) {
                throw new IllegalStateException("已保存的事件内容不完整");
            }
            return json.treeToValue(node, ExecutionEvent.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("已保存的事件无法读取", invalid);
        }
    }
}
