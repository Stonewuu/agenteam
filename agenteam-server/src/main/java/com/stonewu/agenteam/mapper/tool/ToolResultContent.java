package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;

import java.util.ArrayList;

/**
 * 完整原文与用于后续授权的来源字段分别保留，预览不充当完整结果。
 */
public final class ToolResultContent {
    public static final String REFERENCE = "_toolResultFile";

    private ToolResultContent() {
    }

    public static boolean hasText(JsonNode value) {
        if (value.isTextual() || value.path("content").isTextual() || value.path("text").isTextual()) {
            return true;
        }
        return value.path("content").isArray() && !value.path("content").isEmpty()
            && value.path("content").findValues("text").stream().anyMatch(JsonNode::isTextual);
    }

    public static String text(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.path("content").isTextual()) {
            return value.path("content").asText();
        }
        if (value.path("text").isTextual()) {
            return value.path("text").asText();
        }
        if (value.path("content").isArray()) {
            var parts = new ArrayList<String>();
            for (var block : value.path("content")) {
                if (block.path("text").isTextual()) {
                    parts.add(block.path("text").asText());
                }
            }
            if (!parts.isEmpty()) {
                return String.join("\n\n", parts);
            }
        }
        return value.toPrettyString();
    }

    public static String path(String id, boolean text) {
        return "tool-results/" + id + (text ? "/content.txt" : "/content.json");
    }

    public static ObjectNode reference(String id, String fileId, JsonNode value, long size, int previewBytes) {
        String content = text(value);
        var reference = JsonNodeFactory.instance.objectNode();
        reference.put("truncated", true).put("isError", value.path("isError").asBoolean(false));
        if (fileId != null) {
            reference.put("fileId", fileId);
        }
        String path = path(id, hasText(value));
        reference.put("path", path).put("rawPath", path(id, false)).put("revision", Utf8Text.revision(path, content));
        reference.put("sizeBytes", Utf8Text.size(content)).put("rawSizeBytes", size)
            .put("totalLines", content.isEmpty() ? 0 : content.chars().filter(cp -> cp == '\n').count() + 1);
        String preview = Utf8Text.prefix(content, previewBytes);
        int lines = 0, end = 0;
        for (; end < preview.length(); end++) {
            if (preview.charAt(end) == '\n' && ++lines == 20) {
                end++;
                break;
            }
        }
        reference.put("content", preview.substring(0, end));
        reference.put("previewStartLine", content.isEmpty() ? 0 : 1)
            .put("previewEndOffset", Utf8Text.size(preview.substring(0, end)));
        if (reference.path("previewEndOffset").asInt() < reference.path("sizeBytes").asInt()) {
            reference.put("nextCursor", TextFileDocument.continuation(reference.path("revision").asText(),
                reference.path("previewEndOffset").asInt(), reference.path("totalLines").asInt()));
        }
        if (value.path("url").isTextual()) {
            reference.put("sourceUrl", Utf8Text.prefix(value.path("url").asText(), 2048));
        }
        return reference;
    }

    public static void retainAuthorization(JsonNode value, ObjectNode target, String kind, String toolName) {
        if (kind.equals("knowledge") && value.path("citations").isArray()) {
            target.set("citations", value.path("citations").deepCopy());
        }
        if (kind.equals("data")) {
            var fields = target.putArray("dataFields");
            for (var field : value.path("fields")) {
                fields.addObject().put("name", field.path("name").asText())
                    .put("sensitive", field.path("sensitive").asBoolean());
            }
            if (toolName.equals("data_collections")) {
                target.set("items", value.path("items").deepCopy());
            }
        }
    }

    /**
     * 仅裁掉可选预览，不截断 JSON 结构，也不丢失用于续读的路径。
     */
    public static ObjectNode fitReference(ObjectNode reference, int maximum) {
        ObjectNode result = reference.deepCopy();
        while (Utf8Text.size(result.toString()) > maximum && !result.path("content").asText().isEmpty()) {
            String content = result.path("content").asText();
            result.put("content", Utf8Text.prefix(content, Utf8Text.size(content) / 2));
            int offset = Utf8Text.size(result.path("content").asText());
            result.put("previewEndOffset", offset);
            result.put("nextCursor", TextFileDocument.continuation(result.path("revision").asText(), offset,
                result.path("totalLines").asInt()));
        }
        if (Utf8Text.size(result.toString()) <= maximum) {
            return result;
        }
        result = JsonNodeFactory.instance.objectNode().put("truncated", true)
            .put("isError", reference.path("isError").asBoolean(false))
            .put("path", reference.path("path").asText()).put("content", "");
        // 极小的分配只保留续读路径；不提供会跳过未展示内容的位置。
        int remaining = Math.max(0, maximum - Utf8Text.size(result.toString()));
        result.put("content", Utf8Text.prefix(reference.path("content").asText(), remaining / 6));
        return result;
    }
}
