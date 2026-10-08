package com.stonewu.agenteam.model.execution.response;

/**
 * 每次尝试保留独立输出和实际结束原因。
 */
public record RunAttemptView(String id, int attemptNo, String outputMessageId, String status,
                             String startedAt, String finishedAt, String errorSummary) {
}
