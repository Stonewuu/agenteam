package com.stonewu.agenteam.service.modelprofile;

import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseUsage;
import org.junit.jupiter.api.Test;

import static com.anthropic.core.JsonValue.from;
import static com.anthropic.core.JsonValue.fromJsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 先验证厂商开发库与项目解析库能够共存，计费不能在转换时丢失缓存字段。
 */
class NativeModelSdkCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void responsesPreservesCacheCreationAndReasoning() throws Exception {
        var source = json.readTree("""
            {"input_tokens":10000,"input_tokens_details":{"cached_tokens":2000,"cache_write_tokens":1000},
             "output_tokens":1500,"output_tokens_details":{"reasoning_tokens":500},"total_tokens":11500}
            """);
        var usage = JsonValue.fromJsonNode(source).convert(ResponseUsage.class);
        var restored = JsonValue.from(usage).convert(JsonNode.class);
        assertEquals(1000, restored.path("input_tokens_details").path("cache_write_tokens").asInt());
        assertEquals(500, restored.path("output_tokens_details").path("reasoning_tokens").asInt());
        assertEquals(11500, restored.path("total_tokens").asInt());
    }

    @Test
    void anthropicPreservesBothCacheCreationDurations() throws Exception {
        var source = json.readTree("""
            {"input_tokens":1000,"output_tokens":500,"cache_read_input_tokens":6000,
             "cache_creation_input_tokens":2500,"cache_creation":{"ephemeral_5m_input_tokens":2000,
             "ephemeral_1h_input_tokens":500}}
            """);
        var usage = fromJsonNode(source).convert(Usage.class);
        var restored = from(usage).convert(JsonNode.class);
        assertEquals(2500, restored.path("cache_creation_input_tokens").asInt());
        assertEquals(2000, restored.path("cache_creation").path("ephemeral_5m_input_tokens").asInt());
        assertEquals(500, restored.path("cache_creation").path("ephemeral_1h_input_tokens").asInt());
    }

    @Test
    void geminiExposesCandidateAndThoughtTokensSeparately() {
        var usage = GenerateContentResponseUsageMetadata.fromJson("""
            {"promptTokenCount":12000,"cachedContentTokenCount":8000,"candidatesTokenCount":900,
             "thoughtsTokenCount":600,"totalTokenCount":13500}
            """);
        assertEquals(12000, usage.promptTokenCount().orElseThrow());
        assertEquals(8000, usage.cachedContentTokenCount().orElseThrow());
        assertEquals(1500, usage.candidatesTokenCount().orElseThrow() + usage.thoughtsTokenCount().orElseThrow());
        assertEquals(13500, usage.totalTokenCount().orElseThrow());
    }
}
