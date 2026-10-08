package com.stonewu.agenteam.model.execution.response;

/**
 * 快照中已经包含的步骤，sequence 与该快照的实时读取位置一致。
 */
public record LiveRunStepView(String runId, String messageId, String sequence, RunStepView step) {
}
