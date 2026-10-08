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
import java.util.stream.Collectors;

/**
 * 只合并同一事件类型和关联对象的 delta chunk，保持 start、delta、end 各自独立。
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
     * 正式执行在独立事件前保存全部片段，正文总量达到 4 KiB 时立即保存。
     */
    List<AgentEventRecord> acceptForPersistence(AgentStreamEvent event) {
        var result = new ArrayList<AgentEventRecord>();
        if (!isDelta(event.type())) {
            result.addAll(flush(event.runId()));
        }
        result.addAll(accept(event));
        var state = states.get(event.runId());
        if (state != null && state.pendingBytes() >= 4096) {
            result.addAll(flush(event.runId()));
        }
        return result;
    }

    List<AgentEventRecord> flush(String runId) {
        var state = states.get(runId);
        return state == null ? List.of() : state.flushAll();
    }

    void release(String runId) {
        states.remove(runId);
    }

    List<AgentEventRecord> accept(AgentStreamEvent event) {
        if (event == null) {
            return List.of();
        }
        if (event.runId() == null || event.runId().isBlank()) {
            return List.of(instantaneous(event));
        }
        RunState state = states.computeIfAbsent(event.runId(), ignored -> new RunState());
        List<AgentEventRecord> records = RUN_TERMINAL_TYPES.contains(event.type())
            ? state.closeRun(event)
            : state.accept(event);
        if (RUN_TERMINAL_TYPES.contains(event.type())) {
            states.remove(event.runId(), state);
        }
        return records;
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

        synchronized List<AgentEventRecord> accept(AgentStreamEvent event) {
            if (isDelta(event.type())) {
                String key = chunkKey(event);
                PendingChunks chunks = pending.get(key);
                if (chunks == null) {
                    pending.put(key, new PendingChunks(event.type(), event));
                } else {
                    chunks.add(event);
                }
                return List.of();
            }

            List<AgentEventRecord> records = new ArrayList<>();
            if ("AGENT_END".equals(event.type()) || "MODEL_CALL_END".equals(event.type())) {
                records.addAll(flushForReply(event));
            } else {
                records.addAll(flushForBoundary(event));
            }
            records.add(instantaneous(event));
            return records;
        }

        synchronized List<AgentEventRecord> closeRun(AgentStreamEvent terminal) {
            List<AgentEventRecord> records = pending.values().stream()
                .map(PendingChunks::toRecord)
                .sorted((left, right) -> Long.compare(left.sequence(), right.sequence()))
                .collect(Collectors.toCollection(ArrayList::new));
            pending.clear();
            records.add(instantaneous(terminal));
            return records;
        }

        private List<AgentEventRecord> flushForBoundary(AgentStreamEvent boundary) {
            List<AgentEventRecord> records = new ArrayList<>();
            for (String deltaType : matchingDeltaTypes(boundary)) {
                String key = keyFor(boundary, deltaType, correlationId(boundary, deltaType));
                PendingChunks chunks = pending.remove(key);
                if (chunks == null) {
                    String fallbackKey = findUnambiguousKey(boundary, deltaType);
                    chunks = fallbackKey == null ? null : pending.remove(fallbackKey);
                }
                if (chunks != null) {
                    records.add(chunks.toRecord());
                }
            }
            records.sort((left, right) -> Long.compare(left.sequence(), right.sequence()));
            return records;
        }

        private List<AgentEventRecord> flushForReply(AgentStreamEvent boundary) {
            List<AgentEventRecord> records = new ArrayList<>();
            List<String> keys = pending.entrySet().stream()
                .filter(entry -> entry.getValue().belongsTo(boundary))
                .map(Map.Entry::getKey)
                .toList();
            for (String key : keys) {
                PendingChunks chunks = pending.remove(key);
                if (chunks != null) {
                    records.add(chunks.toRecord());
                }
            }
            records.sort((left, right) -> Long.compare(left.sequence(), right.sequence()));
            return records;
        }

        private String findUnambiguousKey(AgentStreamEvent boundary, String deltaType) {
            String candidate = null;
            for (Map.Entry<String, PendingChunks> entry : pending.entrySet()) {
                if (!entry.getValue().eventType().equals(deltaType)
                    || !entry.getValue().belongsTo(boundary)) {
                    continue;
                }
                if (candidate != null) {
                    return null;
                }
                candidate = entry.getKey();
            }
            return candidate;
        }

        private static List<String> matchingDeltaTypes(AgentStreamEvent boundary) {
            if (boundary.type() == null) {
                return List.of();
            }
            return switch (boundary.type()) {
                case "TEXT_BLOCK_START", "TEXT_BLOCK_END" -> List.of("TEXT_BLOCK_DELTA");
                case "THINKING_BLOCK_START", "THINKING_BLOCK_END" -> List.of("THINKING_BLOCK_DELTA");
                case "DATA_BLOCK_START", "DATA_BLOCK_END" -> List.of("DATA_BLOCK_DELTA");
                case "TOOL_CALL_START", "TOOL_CALL_END" -> List.of("TOOL_CALL_DELTA");
                case "TOOL_RESULT_START", "TOOL_RESULT_END" -> List.of(
                    "TOOL_RESULT_TEXT_DELTA", "TOOL_RESULT_DATA_DELTA");
                default -> List.of();
            };
        }

        private static String correlationId(AgentStreamEvent event, String deltaType) {
            return deltaType.startsWith("TOOL_")
                ? event.toolCallId() : event.blockId();
        }

        private static String keyFor(
            AgentStreamEvent event,
            String deltaType,
            String correlationId) {
            return deltaType + "|" + valueOrEmpty(event.source()) + "|"
                + valueOrEmpty(event.replyId()) + "|" + valueOrEmpty(correlationId);
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

        private String eventType() {
            return eventType;
        }

        private boolean belongsTo(AgentStreamEvent boundary) {
            AgentStreamEvent first = events.getFirst();
            boolean replyMatches = boundary.replyId() == null || boundary.replyId().isBlank()
                || valueOrEmpty(first.replyId()).equals(valueOrEmpty(boundary.replyId()));
            return valueOrEmpty(first.source()).equals(valueOrEmpty(boundary.source()))
                && replyMatches;
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
