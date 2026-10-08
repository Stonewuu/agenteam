package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.model.execution.response.LiveExecutionEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 实时事件与累计状态使用独立格式，不改变数据库旧事件的解码。
 */
@Component
public class LiveEventCodec {
    private final ObjectMapper json;

    public LiveEventCodec(ObjectMapper json) {
        this.json = json;
    }

    public record Mutation(String id, String runId, String objectId, String kind, String expectedRevision,
                           String revision, String state, String text, String envelope, String activeChange,
                           long databaseVersion) {
    }

    public Mutation change(RunRecord run, ExecutionChange change, Instant now) {
        if (change.block() == null) {
            throw new IllegalArgumentException("实时片段必须对应内容块");
        }
        var block = change.block();
        String type = change.delta() == null ? "block.updated" : "message.delta";
        Object payload = change.delta() == null ? Map.of("messageId", run.outputMessageId(), "block", block)
            : Map.of("messageId", run.outputMessageId(), "blockId", block.id(), "baseRevision", change.baseRevision(),
            "revision", block.revision(), "delta", change.delta());
        String id = UUID.randomUUID().toString();
        var state = json.createObjectNode().put("messageId", run.outputMessageId());
        state.set("block", json.valueToTree(block));
        ((ObjectNode) state.path("block")).put("text", "");
        return new Mutation(id, run.id(), "block:" + run.outputMessageId() + ":" + block.id(),
            change.delta() == null ? "block" : "delta", change.baseRevision() == null ? "" : change.baseRevision(),
            block.revision(), write(state), change.delta() == null ? block.text() : change.delta(),
            envelope(id, run.enterpriseId(), run.conversationId(), run.id(), now.toString(), type, payload), "", 0);
    }

    public Mutation committed(ExecutionEvent event) {
        long version = Long.parseLong(event.sequence());
        ObjectNode state = json.valueToTree(event.payload());
        String kind = "object", text = "", revision = event.sequence(), id;
        switch (event.type()) {
            case "block.updated" -> {
                kind = "block";
                id = "block:" + state.path("messageId").asText() + ":" + state.path("block").path("id").asText();
                revision = state.path("block").path("revision").asText();
                text = state.path("block").path("text").asText();
                ((ObjectNode) state.path("block")).put("text", "");
            }
            case "message.created" -> id = "message:" + state.path("id").asText();
            case "step.updated" -> {
                id = "step:" + state.path("id").asText();
                var wrapper = json.createObjectNode().put("runId", event.runId());
                wrapper.set("step", state);
                state = wrapper;
            }
            case "approval.created", "approval.resolved" -> id = "approval:" + state.path("id").asText();
            case "attempt.updated" -> id = "attempt:" + state.path("id").asText();
            default -> {
                if (!event.type().startsWith("run.")) {
                    throw new IllegalArgumentException("新实时协议不支持逐片数据库事件：" + event.type());
                }
                id = "run:" + event.runId();
            }
        }
        String active = event.type().startsWith("run.")
            ? switch (state.path("status").asText()) {
            case "completed", "failed", "cancelled" -> "end";
            default -> "start";
        } : "";
        return new Mutation(event.eventId(), event.runId(), id, kind, "", revision, write(state), text,
            envelope(event.eventId(), event.enterpriseId(), event.conversationId(), event.runId(),
                event.createdAt(), event.type(), event.payload()), active, version);
    }

    private String envelope(String id, String enterprise, String conversation, String run, String created,
                            String type, Object payload) {
        var node = json.createObjectNode().put("protocolVersion", 2).put("eventId", id).put("enterpriseId", enterprise)
            .put("conversationId", conversation).put("runId", run).put("createdAt", created).put("type", type);
        node.set("payload", json.valueToTree(payload));
        return write(node);
    }

    public JsonNode state(String stored, String text) {
        var value = read(stored);
        if (value.has("messageId") && value.path("block").isObject()) {
            ((ObjectNode) value.path("block")).put("text", text);
        }
        return value;
    }

    public LiveExecutionEvent decode(String value) {
        try {
            return json.readValue(value, LiveExecutionEvent.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("实时事件内容无法读取", invalid);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("累计输出内容无法读取", invalid);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("实时事件内容无法编码", invalid);
        }
    }
}
