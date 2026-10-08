package com.stonewu.agenteam.model.execution.response;

import com.stonewu.agenteam.model.execution.entity.RunRecord;

/**
 * 公开执行状态，隐藏模型参数、租约和内部配置。
 */
public record RunView(String id, String conversationId, String inputMessageId, String outputMessageId,
                      String status, String mode, int currentAttemptNo, boolean hasStepErrors,
                      String startedAt, String finishedAt, String nextAttemptAt,
                      String errorCode, String errorMessage, boolean canRetry, String lastSequence) {
    public static RunView from(RunRecord run, boolean canRetry) {
        return new RunView(run.id(), run.conversationId(), run.inputMessageId(), run.outputMessageId(),
            run.status(), run.mode(), run.currentAttemptNo(), run.hasStepErrors(),
            run.startedAt() == null ? null : run.startedAt().toString(),
            run.finishedAt() == null ? null : run.finishedAt().toString(),
            run.nextAttemptAt() == null ? null : run.nextAttemptAt().toString(),
            run.errorCode(), run.errorMessage(), canRetry, Long.toString(run.lastSequence()));
    }
}
