package com.stonewu.agenteam.model.execution.response;

/**
 * 提交事务成功，只表示已排队。
 */
public record RunAccepted(String conversationId, String runId, String inputMessageId,
                          String outputMessageId, String status, String lastSequence) {
}
