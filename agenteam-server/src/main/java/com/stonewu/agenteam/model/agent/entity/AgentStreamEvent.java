package com.stonewu.agenteam.model.agent.entity;

import java.util.Map;

/**
 * AgentScope 原始事件的内部结构，用于按来源和回复编号合并文本。
 */
public record AgentStreamEvent(String id, String type, String source, String replyId, String blockId, String delta,
                               String toolCallId, String toolCallName, String text, String state, String error,
                               String createdAt,
                               Map<String, Object> metadata, Map<String, Object> details, String conversationId,
                               String runId, Long sequence) {
    /**
     * 框架事件尚未绑定平台执行时，只包含原始内容和关联字段。
     */
    public AgentStreamEvent(String id, String type, String source, String replyId, String blockId, String delta,
                            String toolCallId, String toolCallName, String text, String state, String error,
                            String createdAt,
                            Map<String, Object> metadata, Map<String, Object> details) {
        this(id, type, source, replyId, blockId, delta, toolCallId, toolCallName, text, state, error, createdAt,
            metadata, details, null, null, null);
    }

    public AgentStreamEvent withExecution(String conversation, String run, long order) {
        return new AgentStreamEvent(id, type, source, replyId, blockId, delta, toolCallId, toolCallName, text, state,
            error,
            createdAt, metadata, details, conversation, run, order);
    }
}
