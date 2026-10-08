package com.stonewu.agenteam.model.execution.response;

import java.util.List;
import java.util.Map;

/**
 * 数据库已保存的消息与公开内容块。
 */
public record MessageView(String id, String runId, int attemptNo, String role, String content,
                          String status, List<ContentBlock> blocks, List<Map<String, Object>> attachments,
                          String feedback, String createdAt, String updatedAt) {
}
