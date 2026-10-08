package com.stonewu.agenteam.model.agent.entity;

import java.util.Map;

/**
 * 一个框架事件或同类文本增量的合并结果，编号来自框架，公开序号由数据库另行分配。
 */
public record AgentEventRecord(
    String eventId,
    String conversationId,
    String runId,
    long sequence,
    String eventType,
    String source,
    String replyId,
    String blockId,
    String toolCallId,
    String toolCallName,
    String text,
    String state,
    String error,
    Map<String, Object> metadata,
    Map<String, Object> details,
    String startedAt,
    String finishedAt,
    boolean complete,
    String completionReason,
    String mergedDelta) {

    public AgentEventRecord {
        metadata = metadata == null ? Map.of() : metadata;
        details = details == null ? Map.of() : details;
    }

}
