package com.stonewu.agenteam.model.execution.response;

import java.util.List;

/**
 * 快照随附的单次执行步骤与尝试记录，减少前端逐轮拆发步骤查询。
 */
public record RunStepHistoryView(List<RunStepView> steps, List<RunAttemptView> attempts, boolean hasMore) {
}
