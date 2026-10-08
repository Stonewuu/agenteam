package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;

/**
 * 本次等待的真实工具和流程节点确认，提交到同一执行的暂停事务。
 */
public final class ExecutionConfirmations {
    private ExecutionConfirmations() {
    }

    public record ToolConfirmation(String sessionId, List<ToolUseBlock> calls) {
        public ToolConfirmation {
            calls = List.copyOf(calls);
        }
    }

    public record WorkflowConfirmation(String stepId, String requestHash, RunApprovalView.Summary summary) {
    }
}
