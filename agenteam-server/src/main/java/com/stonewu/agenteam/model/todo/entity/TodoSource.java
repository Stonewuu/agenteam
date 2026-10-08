package com.stonewu.agenteam.model.todo.entity;

/**
 * 来源编号仅用于关联，不授予会话或执行内容的查看权。
 */
public record TodoSource(String type, String conversationId, String messageId, String runId) {
}
