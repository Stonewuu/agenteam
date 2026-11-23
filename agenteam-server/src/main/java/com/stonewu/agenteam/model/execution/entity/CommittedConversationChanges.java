package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.execution.response.ExecutionEvent;

import java.util.List;

/**
 * 同一会话已经提交的业务变化，版本对应整个事务。
 */
public record CommittedConversationChanges(RunRecord run, long version, List<ExecutionEvent> events) {
}
