package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库只保存一次公共字段；读取时按原标识、时间、顺序和正文边界还原增量。
 */
@Component
public class ExecutionDeltaBatchCodec {
    public static final int STORAGE_VERSION = 2;
    public static final int MAX_FRAGMENTS = 256;
    public static final int MAX_TEXT_BYTES = 65536;
    private static final Set<String> PAYLOAD_FIELDS = Set.of("messageId", "blockId", "baseRevision", "revision",
        "delta");
    private final ResourceJson json;
    private final ExecutionEventCodec events;

    public ExecutionDeltaBatchCodec(ResourceJson json, ExecutionEventCodec events) {
        this.json = json;
        this.events = events;
    }

    public boolean compactable(ExecutionEvent event) {
        if (event.protocolVersion() != 1 || !"message.delta".equals(event.type())
            || !event.payload().keySet().equals(PAYLOAD_FIELDS)
            || !event.payload().values().stream().allMatch(String.class::isInstance)) {
            return false;
        }
        long base = number((String) event.payload().get("baseRevision"));
        return base > 0 && base < Long.MAX_VALUE
            && number((String) event.payload().get("revision")) == base + 1
            && bytes((String) event.payload().get("delta")) <= 4096;
    }

    public boolean canAppend(List<ExecutionEvent> batch, ExecutionEvent next) {
        if (batch.isEmpty() || batch.size() >= MAX_FRAGMENTS || !compactable(next)) {
            return false;
        }
        var last = batch.getLast();
        if (!compactable(last) || !last.enterpriseId().equals(next.enterpriseId())
            || !last.conversationId().equals(next.conversationId()) || !last.runId().equals(next.runId())
            || number(last.sequence()) == Long.MAX_VALUE || number(last.sequence()) + 1 != number(next.sequence())
            || !last.payload().get("messageId").equals(next.payload().get("messageId"))
            || !last.payload().get("blockId").equals(next.payload().get("blockId"))
            || !last.payload().get("revision").equals(next.payload().get("baseRevision"))) {
            return false;
        }
        int total = bytes((String) next.payload().get("delta"));
        for (var event : batch) {
            total += bytes((String) event.payload().get("delta"));
        }
        return total <= MAX_TEXT_BYTES;
    }

    public ExecutionEventCodec.Stored encode(List<ExecutionEvent> batch) {
        if (batch.isEmpty() || batch.size() > MAX_FRAGMENTS || !compactable(batch.getFirst())) {
            throw new IllegalArgumentException(Messages.text("error.execution.executionDeltaBatchCodec.encode.1"));
        }
        var first = batch.getFirst();
        var value = (ObjectNode) json.tree(Map.of(
            "storageVersion", STORAGE_VERSION, "protocolVersion", first.protocolVersion(),
            "enterpriseId", first.enterpriseId(), "conversationId", first.conversationId(),
            "runId", first.runId(), "firstSequence", first.sequence(),
            "messageId", first.payload().get("messageId"), "blockId", first.payload().get("blockId"),
            "baseRevision", first.payload().get("baseRevision")));
        var fragments = value.putArray("fragments");
        var text = new StringBuilder();
        int textBytes = 0;
        for (int i = 0; i < batch.size(); i++) {
            var event = batch.get(i);
            if (i > 0 && !canAppend(List.of(batch.get(i - 1)), event)) {
                throw new IllegalArgumentException(Messages.text("error.execution.executionDeltaBatchCodec.encode.2"));
            }
            String part = (String) event.payload().get("delta");
            textBytes += bytes(part);
            if (textBytes > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException(Messages.text("error.execution.executionDeltaBatchCodec.encode.3"));
            }
            text.append(part);
            fragments.addArray().add(event.eventId()).add(event.createdAt()).add(text.length());
        }
        value.put("delta", text.toString());
        return new ExecutionEventCodec.Stored(json.write(value), json.hash(value));
    }

    public List<ExecutionEvent> decode(int version, String data, String hash) {
        if (version == 1) {
            return List.of(events.decode(data, hash));
        }
        if (version != STORAGE_VERSION) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.1"));
        }
        var value = json.read(data);
        if (value == null || hash == null || !hash.equals(json.hash(value))
            || value.path("storageVersion").asInt() != STORAGE_VERSION
            || value.path("protocolVersion").asInt() != 1) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.2"));
        }
        String enterprise = text(value, "enterpriseId"), conversation = text(value, "conversationId");
        String run = text(value, "runId"), message = text(value, "messageId"), block = text(value, "blockId");
        long sequence = number(text(value, "firstSequence")), revision = number(text(value, "baseRevision"));
        String delta = text(value, "delta");
        var fragments = value.path("fragments");
        if (!fragments.isArray() || fragments.isEmpty() || fragments.size() > MAX_FRAGMENTS
            || bytes(delta) > MAX_TEXT_BYTES || sequence < 1 || revision < 1
            || sequence > Long.MAX_VALUE - fragments.size() + 1 || revision > Long.MAX_VALUE - fragments.size()) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.3"));
        }
        var result = new ArrayList<ExecutionEvent>(fragments.size());
        int start = 0;
        for (var fragment : fragments) {
            if (!fragment.isArray() || fragment.size() != 3 || !fragment.get(0).isTextual()
                || fragment.get(0).asText().isBlank() || !fragment.get(1).isTextual()
                || !fragment.get(2).isIntegralNumber() || !fragment.get(2).canConvertToInt()) {
                throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.4"));
            }
            int end = fragment.get(2).intValue();
            if (end < start || end > delta.length()) {
                throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.5"));
            }
            String part = delta.substring(start, end);
            if (bytes(part) > 4096) {
                throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.6"));
            }
            var payload = Map.<String, Object>of("messageId", message, "blockId", block,
                "baseRevision", Long.toString(revision), "revision", Long.toString(++revision), "delta", part);
            result.add(new ExecutionEvent(1, fragment.get(0).textValue(), enterprise, conversation, run,
                Long.toString(sequence++), fragment.get(1).textValue(), "message.delta", payload));
            start = end;
        }
        if (start != delta.length()) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.decode.7"));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.text.1", field));
        }
        return node.path(field).textValue();
    }

    private static long number(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.number.1"));
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(Messages.text("error.execution.executionDeltaBatchCodec.number.2"), invalid);
        }
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
