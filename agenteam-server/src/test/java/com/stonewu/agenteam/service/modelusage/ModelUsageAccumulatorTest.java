package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelUsageAccumulatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cumulativeCountsReplaceEarlierValuesAndMissingFieldsDoNotEraseInput() throws Exception {
        var accumulator = new ModelUsageAccumulator("anthropic", "standard", Set.of());
        accumulator.accept(json.readTree("""
            {"input_tokens":100,"cache_read_input_tokens":20,"cache_creation_input_tokens":0,"output_tokens":10}
            """));
        accumulator.accept(json.readTree("{\"output_tokens\":30}"));
        accumulator.accept(json.readTree("{\"output_tokens\":50}"));
        assertEquals("partial", accumulator.snapshot().status());
        accumulator.finish();
        var snapshot = accumulator.snapshot();
        assertEquals("complete", snapshot.status());
        assertEquals(120L, snapshot.usage().inputTokens());
        assertEquals(50L, snapshot.usage().outputTokens());
        assertEquals(170L, snapshot.usage().totalTokens());
        accumulator.accept(json.readTree("{\"output_tokens\":50}"));
        accumulator.finish();
        assertEquals(snapshot.sequence(), accumulator.snapshot().sequence());
    }

    @Test
    void emptyUsageIsDifferentFromExplicitZeroEvenAfterCompletion() throws Exception {
        var accumulator = new ModelUsageAccumulator("openai", "standard", Set.of("cache_read", "cache_write"));
        accumulator.accept(json.readTree("{}"));
        accumulator.finish();
        assertEquals("not_reported", accumulator.snapshot().status());
        accumulator.accept(json.readTree("{\"prompt_tokens\":0,\"completion_tokens\":0}"));
        assertEquals("complete", accumulator.snapshot().status());
        assertEquals(0L, accumulator.snapshot().usage().totalTokens());
    }

    @Test
    void onlyAllowedNumericFieldsSurviveTheStream() throws Exception {
        var accumulator = new ModelUsageAccumulator("openai", "standard", Set.of());
        accumulator.accept(json.readTree("""
            {"prompt_tokens":"do-not-store-this","completion_tokens":3,
             "message":"private-response","api_key":"private-key"}
            """));
        String captured = json.writeValueAsString(accumulator.snapshot());
        assertEquals("invalid", accumulator.snapshot().status());
        assertFalse(captured.contains("do-not-store-this"));
        assertFalse(captured.contains("private-response"));
        assertFalse(captured.contains("private-key"));
    }

    @Test
    void geminiAudioFactsRetainOnlyTheirNumericCounters() throws Exception {
        var accumulator = new ModelUsageAccumulator("gemini", "standard", Set.of());
        accumulator.accept(json.readTree("""
            {"promptTokenCount":1000,"toolUsePromptTokenCount":0,"cachedContentTokenCount":0,
             "candidatesTokenCount":100,"thoughtsTokenCount":0,
             "promptTokensDetails":[{"modality":"AUDIO","tokenCount":500,"extra":"private-audio-content"}],
             "candidatesTokensDetails":[{"modality":"AUDIO","tokenCount":10}]}
            """));
        accumulator.finish();
        assertEquals(Set.of("audio_input", "audio_output"), Set.copyOf(accumulator.snapshot().usage().unsupportedMeters()));
        assertEquals(500L, accumulator.snapshot().usage().evidence().get("promptTokensDetails.AUDIO"));
        assertFalse(json.writeValueAsString(accumulator.snapshot()).contains("private-audio-content"));
    }
}
