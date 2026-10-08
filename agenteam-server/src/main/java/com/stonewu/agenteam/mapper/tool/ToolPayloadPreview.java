package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.model.execution.response.LiveExecutionEvent;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.service.file.Utf8Text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话初始数据只携带预览；原始脱敏内容仍保存在工具调用记录和附件中。
 */
public final class ToolPayloadPreview {
    public static final int MAX_BYTES = 2048;

    private ToolPayloadPreview() {
    }

    public static String text(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (Utf8Text.size(content) <= MAX_BYTES) {
            return content;
        }
        return JsonNodeFactory.instance.objectNode().put("truncated", true)
            .put("content", Utf8Text.prefix(content, (MAX_BYTES - 64) / 6)).toString();
    }

    public static String value(JsonNode content) {
        if (content == null) {
            return "";
        }
        String full = content.toString();
        if (Utf8Text.size(full) <= MAX_BYTES) {
            return full;
        }
        var preview = JsonNodeFactory.instance.objectNode().put("truncated", true);
        for (String key : List.of("url", "path", "query", "task", "prompt", "name", "command", "working_directory")) {
            if (content.path(key).isTextual()) {
                preview.put(key, Utf8Text.prefix(content.path(key).asText(), 100));
                if (Utf8Text.size(preview.toString()) > MAX_BYTES - 800) {
                    preview.remove(key);
                }
            }
        }
        preview.put("content", Utf8Text.prefix(ToolResultContent.text(content), 120));
        return preview.toString();
    }

    public static ContentBlock block(ContentBlock block) {
        var tool = block.tool();
        if (tool == null) {
            return block;
        }
        return new ContentBlock(block.id(), block.type(), block.parentBlockId(), block.displayOrder(), block.revision(),
            block.text(), block.status(),
            block.stepId(), block.approvalId(), block.file(), block.citation(), block.label(),
            new ToolBlockDetails(tool.toolCallId(), tool.name(), tool.sourceKind(),
                text(tool.input()), text(tool.result()), tool.callStatus(), tool.resultStatus()));
    }

    public static MessageView message(MessageView message) {
        return new MessageView(message.id(), message.runId(), message.attemptNo(), message.role(), message.content(),
            message.status(),
            message.blocks().stream().map(ToolPayloadPreview::block).toList(), message.attachments(),
            message.feedback(), message.createdAt(), message.updatedAt());
    }

    /**
     * 只在发送浏览器时缩短旧事件，不能修改数据库、缓存正文或用于核对的摘要。
     */
    public static ExecutionEvent event(ExecutionEvent event) {
        if (!Set.of("block.updated", "message.created").contains(event.type())) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        if (event.type().equals("block.updated")) {
            payload.computeIfPresent("block", (key, value) -> blockMap(value));
        } else if (payload.get("blocks") instanceof List<?> blocks) {
            payload.put("blocks", blocks.stream().map(ToolPayloadPreview::blockMap).toList());
        }
        return new ExecutionEvent(event.protocolVersion(), event.eventId(), event.enterpriseId(),
            event.conversationId(), event.runId(), event.sequence(),
            event.createdAt(), event.type(), payload);
    }

    private static Object blockMap(Object value) {
        if (!(value instanceof Map<?, ?> block) || !(block.get("tool") instanceof Map<?, ?> tool)) {
            return value;
        }
        var limited = copy(tool);
        for (String key : List.of("input", "result")) {
            if (limited.get(key) instanceof String text) {
                limited.put(key, text(text));
            }
        }
        var result = copy(block);
        result.put("tool", limited);
        return result;
    }

    public static LiveExecutionEvent event(LiveExecutionEvent event) {
        var preview = event(
            new ExecutionEvent(1, event.eventId(), event.enterpriseId(), event.conversationId(), event.runId(),
                event.sequence(), event.createdAt(), event.type(), event.payload()));
        return new LiveExecutionEvent(event.protocolVersion(), event.generation(), event.sequence(),
            event.databaseVersion(),
            event.eventId(), event.enterpriseId(), event.conversationId(), event.runId(), event.createdAt(),
            event.type(), preview.payload());
    }

    private static Map<String, Object> copy(Map<?, ?> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put((String) key, item));
        return copy;
    }
}
