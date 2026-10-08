package com.stonewu.agenteam.model.tool.response;

import com.stonewu.agenteam.model.enterprise.response.ActorView;

/**
 * 日志列表只展示实际调用结果，不读取参数和结果正文。
 */
public record ToolCallView(String id, String runId, ActorView actor, String toolName, String status,
                           String operationClass,
                           Long durationMs, String errorSummary, String createdAt, boolean canViewDetails,
                           String source, String conversationId) {
    public ToolCallView withConversation(String id) {
        return new ToolCallView(this.id, runId, actor, toolName, status, operationClass, durationMs, errorSummary,
            createdAt, canViewDetails, source, id);
    }
}
