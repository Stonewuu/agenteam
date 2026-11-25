package com.stonewu.agenteam.model.execution.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 数据库已保存的消息与公开内容块。
 */
public record MessageView(String id, String runId, int attemptNo, String role, String content,
                          String status, List<ContentBlock> blocks, List<Map<String, Object>> attachments,
                          String feedback, String createdAt, String updatedAt,
                          @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean supersededByRetry,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String inputMessageId) {
    public MessageView(String id, String runId, int attemptNo, String role, String content, String status,
                       List<ContentBlock> blocks, List<Map<String, Object>> attachments, String feedback, String createdAt, String updatedAt) {
        this(id, runId, attemptNo, role, content, status, blocks, attachments, feedback, createdAt, updatedAt, false, null);
    }

    public MessageView(String id, String runId, int attemptNo, String role, String content, String status,
                       List<ContentBlock> blocks, List<Map<String, Object>> attachments, String feedback, String createdAt,
                       String updatedAt, boolean supersededByRetry) {
        this(id, runId, attemptNo, role, content, status, blocks, attachments, feedback, createdAt, updatedAt, supersededByRetry, null);
    }

    public MessageView markRetried() {
        return new MessageView(id, runId, attemptNo, role, content, status, blocks, attachments, feedback, createdAt, updatedAt, true, inputMessageId);
    }
}
