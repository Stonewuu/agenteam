package com.stonewu.agenteam.mapper.modelusage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据库只保存已经过白名单过滤的计量证据；恢复计算不读取消息内容。
 */
@Component
public class ModelUsageEvidenceMapper {
    public static final int CURRENT_VERSION = 2;
    private final ObjectMapper json;

    public ModelUsageEvidenceMapper(ObjectMapper json) {
        this.json = json;
    }

    public Encoded encode(ModelTokenUsage usage) {
        var encoded = new Encoded(write(usage.cacheWriteDetails()), write(usage.fieldStates()),
            write(new Evidence(CURRENT_VERSION, usage.evidence(), usage.problems(), usage.unsupportedMeters())));
        int bytes = encoded.cacheDetails().getBytes(StandardCharsets.UTF_8).length
            + encoded.fieldStates().getBytes(StandardCharsets.UTF_8).length + encoded.evidence().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 16384) {
            throw new IllegalStateException("模型用量证据超出 16 KiB 限制。");
        }
        return encoded;
    }

    public ModelTokenUsage decode(ModelUsageRecordRow row) {
        try {
            var details = json.readValue(row.getCacheWriteDetailsJson(), new TypeReference<Map<Integer, Long>>() {});
            var states = json.readValue(row.getFieldStatesJson(), new TypeReference<Map<String, String>>() {});
            var evidence = json.readValue(row.getUsageEvidenceJson(), Evidence.class);
            return new ModelTokenUsage(row.getInputTokens(), row.getUncachedInputTokens(), row.getOutputTokens(),
                row.getCacheReadTokens(), row.getCacheWriteTokens(), row.getReasoningTokens(), row.getToolUsePromptTokens(),
                row.getTotalTokens(), details, states, evidence.fields() == null ? Map.of() : evidence.fields(),
                evidence.problems() == null ? List.of() : evidence.problems(),
                evidence.unsupportedMeters() == null ? List.of() : evidence.unsupportedMeters());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的模型用量无法解析。", failure);
        }
    }

    public ObjectNode restoreSource(ModelTokenUsage previous) {
        var source = JsonNodeFactory.instance.objectNode();
        previous.evidence().forEach((path, value) -> {
            String[] parts = path.split("\\.");
            var parent = source;
            for (int index = 0; index < parts.length - 1; index++) {
                parent = parent.withObject("/" + parts[index]);
            }
            parent.set(parts[parts.length - 1], json.valueToTree(value));
        });
        return source;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("模型用量无法编码。", failure);
        }
    }

    public record Encoded(String cacheDetails, String fieldStates, String evidence) {
    }

    private record Evidence(int parserVersion, Map<String, Object> fields, List<String> problems, List<String> unsupportedMeters) {
    }
}
