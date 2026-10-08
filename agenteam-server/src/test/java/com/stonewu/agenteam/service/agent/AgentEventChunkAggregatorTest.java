package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventChunkAggregatorTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String RUN_ID = "run-1";
    private static final String SOURCE = "agent/main";

    @Test
    void keepsStartDeltaAndEndAsSeparateRows() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

        List<AgentEventRecord> start = aggregator.accept(event(
            1, "TEXT_BLOCK_START", "reply-1", "block-1", null,
            "2026-08-28T10:00:00Z"));
        List<AgentEventRecord> firstDelta = aggregator.accept(event(
            2, "TEXT_BLOCK_DELTA", "reply-1", "block-1", "你好",
            "2026-08-28T10:00:00.010Z"));
        List<AgentEventRecord> secondDelta = aggregator.accept(event(
            3, "TEXT_BLOCK_DELTA", "reply-1", "block-1", "，世界",
            "2026-08-28T10:00:00.020Z"));
        List<AgentEventRecord> end = aggregator.accept(event(
            4, "TEXT_BLOCK_END", "reply-1", "block-1", null,
            "2026-08-28T10:00:00.030Z"));

        assertEquals(1, start.size());
        assertTrue(firstDelta.isEmpty());
        assertTrue(secondDelta.isEmpty());
        assertEquals(2, end.size());

        assertEquals("TEXT_BLOCK_START", start.getFirst().eventType());
        assertEquals("2026-08-28T10:00:00Z", start.getFirst().startedAt());
        assertEquals("2026-08-28T10:00:00Z", start.getFirst().finishedAt());

        AgentEventRecord delta = end.getFirst();
        assertEquals("TEXT_BLOCK_DELTA", delta.eventType());
        assertEquals("2026-08-28T10:00:00.010Z", delta.startedAt());
        assertEquals("2026-08-28T10:00:00.020Z", delta.finishedAt());
        assertEquals("你好，世界", delta.mergedDelta());
        assertEquals(SOURCE, delta.source());
        assertEquals("reply-1", delta.replyId());
        assertEquals("block-1", delta.blockId());

        JsonNode payload = new ObjectMapper().valueToTree(delta);
        assertTrue(payload.has("source"));
        assertEquals(SOURCE, payload.get("source").asText());
        assertEquals("reply-1", payload.get("replyId").asText());
        assertTrue(payload.has("mergedDelta"));
        assertEquals("block-1", payload.get("blockId").asText());
        assertFalse(payload.has("events"));

        assertEquals("TEXT_BLOCK_END", end.get(1).eventType());
        assertEquals("2026-08-28T10:00:00.030Z", end.get(1).startedAt());
        assertEquals("2026-08-28T10:00:00.030Z", end.get(1).finishedAt());
    }

    @Test
    void doesNotMergeDifferentDeltaTypesOrDifferentBlocks() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

        assertTrue(aggregator.accept(event(1, "TEXT_BLOCK_DELTA", "reply-1", "block-1",
            "第一段", "2026-08-28T10:00:00Z")).isEmpty());
        assertTrue(aggregator.accept(event(2, "THINKING_BLOCK_DELTA",
            "reply-1", "thinking-1", "思考", "2026-08-28T10:00:00.010Z")).isEmpty());
        assertTrue(aggregator.accept(event(3, "TEXT_BLOCK_DELTA",
            "reply-1", "block-2", "第二段", "2026-08-28T10:00:00.020Z")).isEmpty());
        List<AgentEventRecord> firstText = aggregator.accept(event(4, "TEXT_BLOCK_END",
            "reply-1", "block-1", null, "2026-08-28T10:00:00.030Z"));
        List<AgentEventRecord> thinking = aggregator.accept(event(5, "THINKING_BLOCK_END",
            "reply-1", "thinking-1", null, "2026-08-28T10:00:00.040Z"));
        List<AgentEventRecord> secondText = aggregator.accept(event(6, "TEXT_BLOCK_END",
            "reply-1", "block-2", null, "2026-08-28T10:00:00.050Z"));

        assertEquals("TEXT_BLOCK_DELTA", firstText.getFirst().eventType());
        assertEquals("TEXT_BLOCK_END", firstText.get(1).eventType());
        assertEquals("THINKING_BLOCK_DELTA", thinking.getFirst().eventType());
        assertEquals("THINKING_BLOCK_END", thinking.get(1).eventType());
        assertEquals("TEXT_BLOCK_DELTA", secondText.getFirst().eventType());
        assertEquals("TEXT_BLOCK_END", secondText.get(1).eventType());
        assertEquals(SOURCE, firstText.getFirst().source());
        assertEquals("reply-1", firstText.getFirst().replyId());
        assertEquals(SOURCE, thinking.getFirst().source());
        assertEquals("reply-1", thinking.getFirst().replyId());
        assertEquals(SOURCE, secondText.getFirst().source());
        assertEquals("reply-1", secondText.getFirst().replyId());
    }

    @Test
    void mergesSameDeltaAcrossInterleavedNonDeltaEvents() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

        aggregator.accept(event(1, "TEXT_BLOCK_DELTA", "reply-1", "block-1",
            "第一段", "2026-08-28T10:00:00Z"));
        List<AgentEventRecord> custom = aggregator.accept(event(
            2, "CUSTOM", "reply-1", null, null, "2026-08-28T10:00:00.010Z"));
        aggregator.accept(event(3, "TEXT_BLOCK_DELTA", "reply-1", "block-1",
            "第二段", "2026-08-28T10:00:00.020Z"));
        List<AgentEventRecord> records = aggregator.accept(event(
            4, "TEXT_BLOCK_END", "reply-1", "block-1", null,
            "2026-08-28T10:00:00.030Z"));

        assertEquals("CUSTOM", custom.getFirst().eventType());
        assertEquals("TEXT_BLOCK_DELTA", records.getFirst().eventType());
        assertEquals(SOURCE, records.getFirst().source());
        assertEquals("reply-1", records.getFirst().replyId());
        assertEquals("TEXT_BLOCK_END", records.get(1).eventType());
    }

    @Test
    void usesUniquePendingDeltaWhenBoundaryReplyIdIsMissing() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

        aggregator.accept(event(1, "TEXT_BLOCK_DELTA", "reply-1", "block-1",
            "内容", "2026-08-28T10:00:00Z"));
        List<AgentEventRecord> records = aggregator.accept(event(
            2, "TEXT_BLOCK_END", null, "block-1", null,
            "2026-08-28T10:00:00.010Z"));

        assertEquals(2, records.size());
        assertEquals("TEXT_BLOCK_DELTA", records.getFirst().eventType());
        assertEquals("TEXT_BLOCK_END", records.get(1).eventType());
    }

    @Test
    void savesEachDocumentedStandaloneEventSeparately() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();
        List<String> eventTypes = List.of(
            "AGENT_RESULT", "EXCEED_MAX_ITERS", "REQUEST_STOP", "SUBAGENT_EXPOSED",
            "HINT_BLOCK", "ALL_TOOLS_DENIED", "CUSTOM", "run.created", "run.started");

        long sequence = 10;
        for (String type : eventTypes) {
            List<AgentEventRecord> records = aggregator.accept(event(
                sequence++, type, "reply-1", null, null, "2026-08-28T10:00:00Z"));
            assertEquals(1, records.size(), type);
            assertEquals(type, records.getFirst().eventType());
            assertEquals(SOURCE, records.getFirst().source());
            assertEquals("reply-1", records.getFirst().replyId());
            assertEquals(records.getFirst().startedAt(), records.getFirst().finishedAt());
        }
    }

    @Test
    void flushesPendingDeltaBatchBeforeRunTerminalEvent() {
        AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

        aggregator.accept(event(1, "TOOL_CALL_DELTA", "reply-1", null,
            "{\"path\":", "2026-08-28T10:00:00Z", "tool-1"));
        List<AgentEventRecord> records = aggregator.accept(event(
            2, "run.failed", null, null, null, "2026-08-28T10:00:01Z"));

        assertEquals(2, records.size());
        assertEquals("TOOL_CALL_DELTA", records.getFirst().eventType());
        assertEquals("2026-08-28T10:00:00Z", records.getFirst().finishedAt());
        assertEquals("run.failed", records.get(1).eventType());
    }

    private static AgentStreamEvent event(
        long sequence,
        String type,
        String replyId,
        String blockId,
        String delta,
        String createdAt) {
        return event(sequence, type, replyId, blockId, delta, createdAt, null);
    }

    private static AgentStreamEvent event(
        long sequence,
        String type,
        String replyId,
        String blockId,
        String delta,
        String createdAt,
        String toolCallId) {
        return new AgentStreamEvent(
            "event-" + sequence, type, SOURCE, replyId, blockId, delta, toolCallId, null,
            null, null, null, createdAt, Map.of(), Map.of(),
            CONVERSATION_ID, RUN_ID, sequence);
    }
}
