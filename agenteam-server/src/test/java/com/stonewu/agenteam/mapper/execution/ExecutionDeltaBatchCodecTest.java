package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionDeltaBatchCodecTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final ExecutionEventCodec events = new ExecutionEventCodec(new ObjectMapper(), json);
    private final ExecutionDeltaBatchCodec batches = new ExecutionDeltaBatchCodec(json, events);

    @Test
    void restoresEveryOriginalFieldIncludingEmptyUnicodeAndLargeSequences() {
        long first = 9_007_199_254_740_993L;
        var original = List.of(event(first, 1, "中文🙂\n"), event(first + 1, 2, ""), event(first + 2, 3, "\"反斜线\\与结尾"));
        var stored = batches.encode(original);
        assertEquals(original, batches.decode(2, stored.data(), stored.hash()));
        var legacy = events.encode(original.getFirst());
        assertEquals(List.of(original.getFirst()), batches.decode(1, legacy.data(), legacy.hash()));
        assertEquals("中文🙂\n\"反斜线\\与结尾", json.read(stored.data()).path("delta").asText());
    }

    @Test
    void compactStorageRemovesRepeatedMetadataAndBoundsBothCountAndBytes() {
        var original = new ArrayList<ExecutionEvent>();
        for (int i = 0; i < 256; i++) {
            original.add(event(i + 1, i + 1, "一小段流式内容🙂"));
        }
        var stored = batches.encode(original);
        int before = original.stream().map(events::encode).mapToInt(value -> value.data().getBytes(StandardCharsets.UTF_8).length).sum();
        int after = stored.data().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(after < before * 0.35, "公共字段应只保存一次，典型小片段至少减少 65% 正文存储");
        assertEquals(original, batches.decode(2, stored.data(), stored.hash()));
        assertFalse(batches.canAppend(original, event(257, 257, "下一批")));
        var full = new ArrayList<ExecutionEvent>();
        for (int i = 0; i < 16; i++) {
            full.add(event(i + 1, i + 1, "a".repeat(4096)));
        }
        assertFalse(batches.canAppend(full, event(17, 17, "a")));
        assertEquals(full, batches.decode(2, batches.encode(full).data(), batches.encode(full).hash()));
    }

    @Test
    void neverMergesDifferentObjectsOrDiscontinuousSequencesAndRevisions() {
        var first = event(1, 1, "前");
        var next = event(2, 2, "后");
        assertTrue(batches.canAppend(List.of(first), next));
        assertFalse(batches.canAppend(List.of(first), event(3, 2, "后")));
        assertFalse(batches.canAppend(List.of(first), event(2, 3, "后")));
        for (String field : List.of("messageId", "blockId")) {
            var payload = new HashMap<>(next.payload());
            payload.put(field, "其他对象");
            assertFalse(batches.canAppend(List.of(first), new ExecutionEvent(1, next.eventId(), next.enterpriseId(),
                next.conversationId(), next.runId(), next.sequence(), next.createdAt(), next.type(), payload)));
        }
        assertFalse(batches.canAppend(List.of(first), new ExecutionEvent(1, next.eventId(), "其他企业", next.conversationId(),
            next.runId(), next.sequence(), next.createdAt(), next.type(), next.payload())));
        assertFalse(batches.canAppend(List.of(first), new ExecutionEvent(1, next.eventId(), next.enterpriseId(), "其他对话",
            next.runId(), next.sequence(), next.createdAt(), next.type(), next.payload())));
        assertFalse(batches.canAppend(List.of(first), new ExecutionEvent(1, next.eventId(), next.enterpriseId(), next.conversationId(),
            "其他执行", next.sequence(), next.createdAt(), next.type(), next.payload())));
        var extra = new HashMap<>(next.payload());
        extra.put("futureField", "新字段不能在合并时丢失");
        assertFalse(batches.compactable(new ExecutionEvent(1, next.eventId(), next.enterpriseId(), next.conversationId(),
            next.runId(), next.sequence(), next.createdAt(), next.type(), extra)));
    }

    @Test
    void rejectsCorruptionUnknownVersionAndInvalidFragmentBoundaries() {
        var stored = batches.encode(List.of(event(1, 1, "前"), event(2, 2, "后")));
        assertThrows(IllegalStateException.class, () -> batches.decode(2, stored.data(), "错误摘要"));
        assertThrows(IllegalStateException.class, () -> batches.decode(3, stored.data(), stored.hash()));
        var modified = json.read(stored.data());
        ((ArrayNode) modified.path("fragments").get(1)).set(2, json.tree(99));
        assertThrows(IllegalStateException.class, () -> batches.decode(2, json.write(modified), json.hash(modified)));
        assertThrows(IllegalArgumentException.class, () -> batches.encode(List.of(event(1, 1, "前"), event(3, 2, "后"))));
    }

    private ExecutionEvent event(long sequence, long revision, String delta) {
        return new ExecutionEvent(1, UUID.randomUUID().toString(), "enterprise-00000000-0000-0000", "conversation-00000000-0000-0000",
            "run-00000000-0000-0000", Long.toString(sequence), Instant.ofEpochSecond(1000, revision).toString(), "message.delta",
            Map.of("messageId", "message-00000000-0000-0000", "blockId", "block-00000000-0000-0000", "baseRevision", Long.toString(revision),
                "revision", Long.toString(revision + 1), "delta", delta));
    }
}
