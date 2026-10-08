package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.execution.response.ExecutionEvent;

/**
 * 随数据库事务发布，由提交后的通知处理器分发完整变化。
 */
public record CommittedExecutionEvent(RunRecord run, ExecutionEvent event, boolean live) {
}
