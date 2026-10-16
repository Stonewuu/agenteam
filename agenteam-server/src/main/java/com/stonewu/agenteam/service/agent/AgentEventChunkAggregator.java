package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 同一任务中只合并类型、来源和关联对象相同的正文片段。
 */
final class AgentEventChunkAggregator {

    private static final Set<String> DELTA_TYPES = Set.of(
        "TEXT_BLOCK_DELTA",
        "THINKING_BLOCK_DELTA",
        "DATA_BLOCK_DELTA",
        "TOOL_CALL_DELTA",
        "TOOL_RESULT_TEXT_DELTA",
        "TOOL_RESULT_DATA_DELTA");

    private static final Set<String> RUN_TERMINAL_TYPES = Set.of(
        "run.completed", "run.failed", "run.cancelled");

    private final ConcurrentMap<String, RunState> states = new ConcurrentHashMap<>();

    /**
     * 独立事件之前保存全部片段，待保存正文达到 4096 字节时立即保存。
     */
    List<AgentEventRecord> acceptForPersistence(AgentStreamEvent event) {
        if (!isDelta(event.type())) {
            var records = new ArrayList<>(flush(event.runId()));
            records.add(instantaneous(event));
            if (RUN_TERMINAL_TYPES.contains(event.type())) {
                release(event.runId());
            }
            return records;
        }
        var state = states.computeIfAbsent(event.runId(), ignored -> new RunState());
        state.add(event);
        return state.pendingBytes() >= 4096 ? state.flushAll() : List.of();
    }

    List<AgentEventRecord> flush(String runId) {
        var state = states.get(runId);
        return state == null ? List.of() : state.flushAll();
    }

    void release(String runId) {
        states.remove(runId);
    }

    private static boolean isDelta(String type) {
        return type != null && DELTA_TYPES.contains(type);
    }

    private static AgentEventRecord instantaneous(AgentStreamEvent event) {
        return new AgentEventRecord(
            event.id(), event.conversationId(), event.runId(), sequenceOf(event), event.type(),
            event.source(), event.replyId(), event.blockId(), event.toolCallId(), event.toolCallName(),
            event.text(), event.state(), event.error(), event.metadata(), event.details(),
            event.createdAt(), event.createdAt(), true, "instantaneous", null);
    }

    private static String chunkKey(AgentStreamEvent event) {
        String correlation = switch (event.type()) {
            case "TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA", "DATA_BLOCK_DELTA" ->
                valueOrEmpty(event.replyId()) + "|" + valueOrEmpty(event.blockId());
            case "TOOL_CALL_DELTA", "TOOL_RESULT_TEXT_DELTA", "TOOL_RESULT_DATA_DELTA" ->
                valueOrEmpty(event.replyId()) + "|" + valueOrEmpty(event.toolCallId());
            default -> "";
        };
        return event.type() + "|" + valueOrEmpty(event.source()) + "|" + correlation;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long sequenceOf(AgentStreamEvent event) {
        return event.sequence() == null ? 0L : event.sequence();
    }

    private static final class RunState {

        private final Map<String, PendingChunks> pending = new ConcurrentHashMap<>();

        synchronized int pendingBytes() {
            return pending.values().stream().mapToInt(chunks -> chunks.bytes).sum();
        }

        synchronized List<AgentEventRecord> flushAll() {
            var result = pending.values().stream().map(PendingChunks::toRecord)
                .sorted((left, right) -> Long.compare(left.sequence(), right.sequence())).toList();
            pending.clear();
            return result;
        }

        synchronized void add(AgentStreamEvent event) {
            String key = chunkKey(event);
            PendingChunks chunks = pending.get(key);
            if (chunks == null) {
                pending.put(key, new PendingChunks(event.type(), event));
            } else {
                chunks.add(event);
            }
        }
    }

    private static final class PendingChunks {

        private final String eventType;
        private final List<AgentStreamEvent> events = new ArrayList<>();
        private int bytes;

        private PendingChunks(String eventType, AgentStreamEvent first) {
            this.eventType = eventType;
            add(first);
        }

        private void add(AgentStreamEvent event) {
            events.add(event);
            if (event.delta() != null) {
                bytes += event.delta().getBytes(StandardCharsets.UTF_8).length;
            }
        }

        private AgentEventRecord toRecord() {
            AgentStreamEvent first = events.getFirst();
            AgentStreamEvent last = events.getLast();
            return new AgentEventRecord(
                last.id(), last.conversationId(), last.runId(), sequenceOf(last), eventType,
                first.source(), first.replyId(), first.blockId(), first.toolCallId(), first.toolCallName(),
                first.text(), first.state(), first.error(), first.metadata(), first.details(),
                first.createdAt(), last.createdAt(), true, "delta_chunks", mergedDelta());
        }

        private String mergedDelta() {
            if (events.stream().anyMatch(event -> event.delta() == null)) {
                return null;
            }
            return events.stream()
                .map(AgentStreamEvent::delta)
                .reduce(String::concat)
                .orElse("");
        }
    }
}
