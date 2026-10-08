package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventCodec;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPayloadPreviewTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void previewRemainsValidJsonEvenForQuotesAndControlCharacters() throws Exception {
        var value = json.createObjectNode();
        for (String field : List.of("url", "path", "query", "task", "prompt", "name", "command", "working_directory")) {
            value.put(field, "\u0001\"文🙂".repeat(3000));
        }
        for (String preview : List.of(ToolPayloadPreview.value(value), ToolPayloadPreview.text(value.toString()))) {
            assertTrue(Utf8Text.size(preview) <= 2048);
            assertTrue(json.readTree(preview).path("truncated").asBoolean());
        }
    }

    @Test
    void replayLimitsLargeOldToolDataWithoutChangingStoredHashOrIdentity() {
        String full = "原始资料".repeat(10000);
        var block = Map.of("type", "tool", "id", "block", "tool", Map.of("name", "read_url", "input", full, "result", full));
        var stored = event("block.updated", Map.of("messageId", "message", "block", block));
        var codec = new ExecutionEventCodec(json, new ResourceJson(json));
        String hash = codec.encode(stored).hash();
        var visible = ToolPayloadPreview.event(stored);
        assertEquals(hash, codec.encode(stored).hash());
        assertEquals(stored.eventId(), visible.eventId());
        assertEquals(stored.sequence(), visible.sequence());
        var tool = json.valueToTree(visible).at("/payload/block/tool");
        assertTrue(Utf8Text.size(tool.path("result").asText()) <= 2048);
        assertEquals(full, json.valueToTree(stored).at("/payload/block/tool/result").asText());
    }

    @Test
    void keepsNormalMessagesIntactButLimitsDuplicatedNativeToolResults() {
        String text = "员工回复".repeat(10000);
        var payload = Map.<String, Object>of("content", text, "blocks", List.of(Map.of("type", "tool", "tool", Map.of("name", "agent_spawn", "result", text))));
        var visible = ToolPayloadPreview.event(event("message.created", payload));
        assertEquals(text, visible.payload().get("content"));
        assertTrue(Utf8Text.size(json.valueToTree(visible).at("/payload/blocks/0/tool/result").asText()) <= 2048);
    }

    private ExecutionEvent event(String type, Map<String, Object> payload) {
        return new ExecutionEvent(1, "event", "enterprise", "conversation", "run", "123", "2026-09-19T00:00:00Z", type, payload);
    }
}
