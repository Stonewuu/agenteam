package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper;
import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实框架事件验证思考内容的版本、分段和恢复，不伪造内部事件名称。
 */
class AgentThinkingEventTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-17T00:00:00Z");
    private final RunRecord run = new RunRecord("run", "enterprise", "conversation", "user", "input", "output", "version", "interactive", "running",
        json.createObjectNode(), 1, 1, 1, false, 3, now, null, null, null, null, null, now);
    private final AgentStreamEventMapper events = new AgentStreamEventMapper();
    private final AgentEventChunkAggregator chunks = new AgentEventChunkAggregator();
    private final AgentPublicEventMapper mapper = new AgentPublicEventMapper(run, "attempt", json, List.of());
    private long sequence;

    @Test
    void realThinkingEventsProduceOneBlockAndCompleteWithTheAccumulatedText() {
        accept(new ThinkingBlockStartEvent("reply", "thinking"));
        accept(new ThinkingBlockDeltaEvent("reply", "thinking", "先核对"));
        accept(new ThinkingBlockDeltaEvent("reply", "thinking", "实际数据。"));
        var changes = accept(new ThinkingBlockEndEvent("reply", "thinking"));

        var blocks = mapper.presentation().blocks().values();
        assertEquals(1, blocks.size());
        var block = blocks.iterator().next();
        assertEquals("thinking", block.type());
        assertEquals("先核对实际数据。", block.text());
        assertEquals("completed", block.status());
        assertEquals("3", block.revision());
        assertEquals("1", changes.getFirst().baseRevision());
        assertEquals("先核对实际数据。", changes.getFirst().delta());
    }

    @Test
    void largeChineseThinkingIsSplitWithoutBreakingCharactersOrRevisions() {
        accept(new ThinkingBlockStartEvent("reply", "thinking"));
        String text = "正在核对🧠。".repeat(1200);
        var changes = accept(new ThinkingBlockDeltaEvent("reply", "thinking", text));
        accept(new ThinkingBlockEndEvent("reply", "thinking"));
        assertTrue(changes.size() > 1);
        StringBuilder combined = new StringBuilder();
        long revision = 1;
        for (var change : changes) {
            assertTrue(change.delta().getBytes(StandardCharsets.UTF_8).length <= 4096);
            assertEquals(Long.toString(revision++), change.baseRevision());
            assertEquals(Long.toString(revision), change.block().revision());
            combined.append(change.delta());
        }
        assertEquals(text, combined.toString());
        assertEquals(text, mapper.presentation().blocks().values().iterator().next().text());
    }

    @Test
    void differentRepliesWithTheSameThinkingIdRemainIndependent() {
        accept(new ThinkingBlockStartEvent("first", "same"));
        accept(new ThinkingBlockDeltaEvent("first", "same", "第一次"));
        accept(new ThinkingBlockStartEvent("second", "same"));
        accept(new ThinkingBlockDeltaEvent("second", "same", "第二次"));
        accept(new ThinkingBlockEndEvent("first", "same"));
        accept(new ThinkingBlockEndEvent("second", "same"));
        var blocks = new ArrayList<>(mapper.presentation().blocks().values());
        assertEquals(List.of("第一次", "第二次"), blocks.stream().map(block -> block.text()).toList());
        assertEquals(2, blocks.stream().map(block -> block.id()).distinct().count());
        assertTrue(blocks.stream().allMatch(block -> block.status().equals("completed")));
    }

    private List<ExecutionChange> accept(AgentEvent event) {
        var changes = new ArrayList<ExecutionChange>();
        for (var record : chunks.acceptForPersistence(events.map(event).withExecution(run.conversationId(), run.id(), ++sequence))) {
            changes.addAll(mapper.accept(record));
        }
        return changes;
    }
}
