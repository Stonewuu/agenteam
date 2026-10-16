package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventChunkAggregatorTest {
    private final AgentEventChunkAggregator aggregator = new AgentEventChunkAggregator();

    @Test
    void independentEventsFlushEarlierTextAndPreserveOrderAndMetadata() {
        var start = accept(1, "TEXT_BLOCK_START", "reply", "block", null, null);
        assertEquals("TEXT_BLOCK_START", start.getFirst().eventType());
        assertTrue(accept(2, "TEXT_BLOCK_DELTA", "reply", "block", null, "你好").isEmpty());
        assertTrue(accept(3, "TEXT_BLOCK_DELTA", "reply", "block", null, "，世界").isEmpty());
        var custom = accept(4, "CUSTOM", "reply", null, null, null);
        assertEquals(List.of("TEXT_BLOCK_DELTA", "CUSTOM"), custom.stream().map(AgentEventRecord::eventType).toList());
        var text = custom.getFirst();
        assertEquals("你好，世界", text.mergedDelta());
        assertEquals(3, text.sequence());
        assertEquals("agent/main", text.source());
        assertEquals("reply", text.replyId());
        assertEquals("block", text.blockId());
        assertEquals(Instant.EPOCH.plusMillis(2).toString(), text.startedAt());
        assertEquals(Instant.EPOCH.plusMillis(3).toString(), text.finishedAt());
        assertTrue(accept(5, "TEXT_BLOCK_DELTA", "reply", "block", null, "后续内容").isEmpty());
        var end = accept(6, "TEXT_BLOCK_END", "reply", "block", null, null);
        assertEquals(List.of("TEXT_BLOCK_DELTA", "TEXT_BLOCK_END"), end.stream().map(AgentEventRecord::eventType).toList());
        assertEquals("后续内容", end.getFirst().mergedDelta());
        assertTrue(aggregator.flush("run").isEmpty());
    }

    @Test
    void interleavedRepliesBlocksAndTypesFlushTogetherWithoutMixingTheirContent() {
        assertTrue(accept(1, "TEXT_BLOCK_DELTA", "first", "same", null, "甲").isEmpty());
        assertTrue(accept(2, "THINKING_BLOCK_DELTA", "first", "same", null, "思考").isEmpty());
        assertTrue(accept(3, "TEXT_BLOCK_DELTA", "second", "same", null, "乙").isEmpty());
        assertTrue(accept(4, "TOOL_CALL_DELTA", "first", null, "tool", "参数").isEmpty());
        assertTrue(accept(5, "TEXT_BLOCK_DELTA", "first", "other", null, "另一段").isEmpty());
        assertTrue(accept(6, "TEXT_BLOCK_DELTA", "first", "same", null, "完成").isEmpty());
        var boundary = accept(7, "TEXT_BLOCK_END", null, "same", null, null);
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L), boundary.stream().map(AgentEventRecord::sequence).toList());
        assertEquals(List.of("思考", "乙", "参数", "另一段", "甲完成"), boundary.subList(0, 5).stream().map(AgentEventRecord::mergedDelta).toList());
        assertEquals("THINKING_BLOCK_DELTA", boundary.get(0).eventType());
        assertEquals("second", boundary.get(1).replyId());
        assertEquals("tool", boundary.get(2).toolCallId());
        assertEquals("other", boundary.get(3).blockId());
        assertEquals("first", boundary.get(4).replyId());
        assertTrue(accept(8, "TOOL_CALL_DELTA", "first", null, "tool", "剩余参数").isEmpty());
        var terminal = accept(9, "run.failed", null, null, null, null);
        assertEquals(List.of("TOOL_CALL_DELTA", "run.failed"), terminal.stream().map(AgentEventRecord::eventType).toList());
        assertEquals("剩余参数", terminal.getFirst().mergedDelta());
        assertTrue(aggregator.flush("run").isEmpty());
    }

    @Test
    void byteThresholdAndExplicitReleasePreserveOtherExecutions() {
        String text = "中".repeat(1365);
        assertEquals(4095, text.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(accept(1, "TEXT_BLOCK_DELTA", "reply", "block", null, text).isEmpty());
        var threshold = accept(2, "TEXT_BLOCK_DELTA", "reply", "block", null, "a");
        assertEquals(1, threshold.size());
        assertEquals(text + "a", threshold.getFirst().mergedDelta());
        assertTrue(aggregator.flush("run").isEmpty());
        assertTrue(accept(3, "TEXT_BLOCK_DELTA", "reply", "block", null, "当前任务").isEmpty());
        aggregator.acceptForPersistence(event(1, "TEXT_BLOCK_DELTA", "reply", "block", null, "其他任务")
            .withExecution("other-conversation", "other-run", 1));
        aggregator.release("run");
        assertTrue(aggregator.flush("run").isEmpty());
        assertEquals("其他任务", aggregator.flush("other-run").getFirst().mergedDelta());
        assertTrue(aggregator.flush("other-run").isEmpty());
    }

    private List<AgentEventRecord> accept(long sequence, String type, String reply, String block, String tool, String delta) {
        return aggregator.acceptForPersistence(event(sequence, type, reply, block, tool, delta));
    }

    private AgentStreamEvent event(long sequence, String type, String reply, String block, String tool, String delta) {
        return new AgentStreamEvent("event-" + sequence, type, "agent/main", reply, block, delta, tool, null,
            null, null, null, Instant.EPOCH.plusMillis(sequence).toString(), Map.of(), Map.of(), "conversation", "run", sequence);
    }
}
