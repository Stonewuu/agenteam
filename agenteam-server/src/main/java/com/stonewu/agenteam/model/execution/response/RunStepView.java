package com.stonewu.agenteam.model.execution.response;

/**
 * 公开步骤只包含稳定层级、顺序、状态和可展示摘要。
 */
public record RunStepView(String id, String parentStepId, String attemptId, String kind, String title,
                          int displayOrder, String status, String publicSummary, String startedAt, String finishedAt,
                          WorkflowStepDetails workflow) {
}
