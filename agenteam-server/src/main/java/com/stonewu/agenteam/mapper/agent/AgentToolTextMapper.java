package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** 从工具原文中提取适合对话显示的文字，流式片段不完整时等待后续内容。 */
public final class AgentToolTextMapper {
    private AgentToolTextMapper() {
    }

    public static String input(ObjectMapper json, String value) {
        try {
            var input = json.readTree(value);
            if (input == null || !input.isObject()) {
                return "";
            }
            return input.has("task") ? input.path("task").asText() : input.path("message").asText();
        } catch (JsonProcessingException incomplete) {
            // 参数片段尚未完整，不把解析错误展示成工具执行错误。
            return "";
        }
    }

    public static String result(ObjectMapper json, String value) {
        if (value.startsWith("\"") || value.startsWith("{")) {
            try {
                var decoded = json.readTree(value);
                if (decoded.isTextual()) {
                    value = decoded.textValue();
                } else if (decoded.path("isError").asBoolean() && decoded.path("content").isTextual()) {
                    return decoded.path("content").asText();
                }
            } catch (JsonProcessingException incomplete) {
                // 工具结果分段到达，保持等待，不输出未完成的结构。
                return "";
            }
        }
        String result = value.replaceFirst(
            "(?s)^(?:(?:agent_key|agent_id|session_id|task_id|status):[^\\r\\n]*(?:\\r?\\n|$))+", "").stripLeading();
        if (List.of("agent_key:", "agent_id:", "session_id:", "task_id:", "status:").stream()
            .anyMatch(header -> header.startsWith(result))) {
            return "";
        }
        return result.replaceFirst("^reply:\\s*", "");
    }
}
