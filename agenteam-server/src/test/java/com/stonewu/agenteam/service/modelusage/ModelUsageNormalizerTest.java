package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelUsageNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelUsageNormalizer normalizer = new ModelUsageNormalizer();

    @Test
    void responsesSubtractsBothCacheKindsWithoutChargingReasoningTwice() throws Exception {
        var usage = parse("openai_responses", """
            {"input_tokens":10000,"input_tokens_details":{"cached_tokens":2000,"cache_write_tokens":1000},
             "output_tokens":1500,"output_tokens_details":{"reasoning_tokens":500},"total_tokens":11500}
            """);
        assertTrue(usage.complete());
        assertEquals(7000L, usage.uncachedInputTokens());
        assertEquals(1500L, usage.outputTokens());
        assertEquals(500L, usage.reasoningTokens());
        assertEquals(11500L, usage.totalTokens());
    }

    @Test
    void anthropicAddsCachesToRawInputAndKeepsCreationDurations() throws Exception {
        var usage = parse("anthropic", """
            {"input_tokens":1000,"output_tokens":500,"cache_read_input_tokens":6000,
             "cache_creation_input_tokens":2500,"cache_creation":{"ephemeral_5m_input_tokens":2000,
             "ephemeral_1h_input_tokens":500}}
            """);
        assertTrue(usage.complete());
        assertEquals(9500L, usage.inputTokens());
        assertEquals(1000L, usage.uncachedInputTokens());
        assertEquals(2500L, usage.cacheWriteTokens());
        assertEquals(Map.of(300, 2000L, 3600, 500L), usage.cacheWriteDetails());
        assertEquals(10000L, usage.totalTokens());
    }

    @Test
    void geminiAddsThoughtsToCandidatesAndDoesNotInventCacheCreation() throws Exception {
        var usage = parse("gemini", """
            {"promptTokenCount":12000,"cachedContentTokenCount":8000,"candidatesTokenCount":900,
             "thoughtsTokenCount":600,"toolUsePromptTokenCount":0,"totalTokenCount":13500}
            """);
        assertTrue(usage.complete());
        assertEquals(1500L, usage.outputTokens());
        assertEquals(4000L, usage.uncachedInputTokens());
        assertNull(usage.cacheWriteTokens());
        assertEquals("not_applicable", usage.fieldStates().get("cache_write"));
        assertEquals(13500L, usage.totalTokens());
    }

    @Test
    void geminiToolInputIsIncludedOnceAndUnusedToolsDoNotMakeUsageIncomplete() throws Exception {
        var usage = parse("gemini", """
            {"promptTokenCount":12000,"cachedContentTokenCount":8000,"candidatesTokenCount":900,
             "thoughtsTokenCount":600,"toolUsePromptTokenCount":300,"totalTokenCount":13800}
            """);
        assertTrue(usage.complete());
        assertEquals(12300L, usage.inputTokens());
        assertEquals(4300L, usage.uncachedInputTokens());
        assertEquals(300L, usage.toolUsePromptTokens());
        assertEquals(13800L, usage.totalTokens());
        assertTrue(usage.unsupportedMeters().isEmpty());

        var missing = parse("gemini", """
            {"promptTokenCount":12000,"cachedContentTokenCount":8000,"candidatesTokenCount":900,
             "thoughtsTokenCount":600}
            """);
        assertEquals(12000L, missing.inputTokens());
        assertNull(missing.toolUsePromptTokens());
        assertEquals("not_applicable", missing.fieldStates().get("tool_use_prompt"));
        assertTrue(missing.complete());
    }

    @Test
    void standardOpenaiUsageDoesNotRequireOptionalCacheCreationOrReasoning() throws Exception {
        var usage = parse("openai", """
            {"prompt_tokens":7388,"completion_tokens":497,"prompt_tokens_details":{"cached_tokens":6272},"total_tokens":7885}
            """);
        assertTrue(usage.complete());
        assertEquals(1116L, usage.uncachedInputTokens());
        assertNull(usage.cacheWriteTokens());
        assertEquals("not_applicable", usage.fieldStates().get("cache_write"));
        assertEquals("not_applicable", usage.fieldStates().get("reasoning"));
        var responses = parse("openai_responses", """
            {"input_tokens":100,"output_tokens":20,"input_tokens_details":{"cached_tokens":30},"total_tokens":120}
            """);
        assertTrue(responses.complete());
        assertEquals(70L, responses.uncachedInputTokens());
    }

    @Test
    void unusedNativeProtocolFeaturesAreNotRequired() throws Exception {
        var anthropic = parse("anthropic", """
            {"input_tokens":100,"output_tokens":20}
            """);
        assertTrue(anthropic.complete());
        assertEquals(100L, anthropic.inputTokens());
        assertEquals("not_applicable", anthropic.fieldStates().get("cache_write"));
        var gemini = parse("gemini", """
            {"promptTokenCount":100,"candidatesTokenCount":20,"totalTokenCount":120}
            """);
        assertTrue(gemini.complete());
        assertEquals(20L, gemini.outputTokens());
        assertEquals("not_applicable", gemini.fieldStates().get("reasoning"));
        assertEquals("not_applicable", gemini.fieldStates().get("cache_read"));
    }

    @Test
    void malformedOptionalCountersAndPartialCreationDetailsRemainVisible() throws Exception {
        var malformed = parse("openai", """
            {"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":30,"cache_write_tokens":"invalid"}}
            """);
        assertFalse(malformed.valid());
        assertFalse(malformed.complete());
        var partial = parse("anthropic", """
            {"input_tokens":100,"output_tokens":20,"cache_creation":{"ephemeral_5m_input_tokens":50}}
            """);
        assertFalse(partial.complete());
        assertNull(partial.inputTokens());
        assertEquals("not_reported", partial.fieldStates().get("cache_write"));
    }

    @Test
    void missingCacheCountsAreNotSilentlyTreatedAsZero() throws Exception {
        String payload = """
            {"prompt_tokens":100,"completion_tokens":30,"total_tokens":130}
            """;
        var unknown = parse("openai", payload);
        assertNull(unknown.cacheReadTokens());
        assertNull(unknown.uncachedInputTokens());
        assertFalse(unknown.complete());
        var unsupported = normalizer.normalize("openai", "standard", json.readTree(payload),
            Set.of("cache_read", "cache_write"));
        assertTrue(unsupported.complete());
        assertEquals(100L, unsupported.uncachedInputTokens());
        assertNull(unsupported.cacheReadTokens());
    }

    @Test
    void explicitZeroRemainsAReportedValue() throws Exception {
        var usage = parse("openai", """
            {"prompt_tokens":0,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},
             "completion_tokens":0,"total_tokens":0}
            """);
        assertTrue(usage.complete());
        assertEquals(0L, usage.cacheReadTokens());
        assertEquals("reported", usage.fieldStates().get("cache_read"));
    }

    @Test
    void deepseekUsesItsDeclaredFormatAndChecksStandardCounters() throws Exception {
        var usage = normalizer.normalize("openai", "deepseek", json.readTree("""
            {"prompt_tokens":10000,"completion_tokens":100,"prompt_cache_hit_tokens":8000,
             "prompt_cache_miss_tokens":2000,"total_tokens":10100}
            """), Set.of());
        assertTrue(usage.complete());
        assertEquals(2000L, usage.uncachedInputTokens());
        assertEquals(8000L, usage.cacheReadTokens());
    }

    @Test
    void inconsistentCountsRemainInvalidInsteadOfBeingClampedToZero() throws Exception {
        var usage = parse("openai_responses", """
            {"input_tokens":10,"input_tokens_details":{"cached_tokens":20,"cache_write_tokens":0},
             "output_tokens":5,"total_tokens":16}
            """);
        assertFalse(usage.valid());
        assertFalse(usage.complete());
        assertNull(usage.uncachedInputTokens());
        assertTrue(usage.problems().contains("INCONSISTENT_TOKEN_TOTAL:input_uncached"));
        assertTrue(usage.problems().contains("INCONSISTENT_TOKEN_TOTAL:total"));
    }

    @Test
    void malformedUsageCannotCopyResponseTextIntoEvidence() throws Exception {
        var usage = parse("openai", """
            {"prompt_tokens":"private-message","completion_tokens":-1,
             "unrelated":{"api_key":"private-key"},"total_tokens":99999999999999999999999999}
            """);
        assertFalse(usage.valid());
        String evidence = json.writeValueAsString(usage.evidence());
        assertFalse(evidence.contains("private-message"));
        assertFalse(evidence.contains("private-key"));
        assertTrue(usage.problems().contains("INVALID_TOKEN_VALUE:input"));
        assertTrue(usage.problems().contains("TOKEN_VALUE_OUT_OF_RANGE:output"));
    }

    private ModelTokenUsage parse(String protocol, String source) throws Exception {
        return normalizer.normalize(protocol, "standard", json.readTree(source), Set.of());
    }
}
