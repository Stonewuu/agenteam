package com.stonewu.agenteam.model.workflow.entity;

import io.agentscope.core.state.State;

import java.util.List;
import java.util.Map;

/**
 * 与同次执行的所有框架会话一起保存；每次调用有独立节点状态。
 */
public record WorkflowRunState(Map<String, WorkflowInvocationState> invocations, List<PendingCall> rootCalls,
                               boolean rootCompleted) implements State {
    public record PendingCall(String id, String name, String input) {
    }

    public WorkflowRunState {
        invocations = Map.copyOf(invocations);
        rootCalls = List.copyOf(rootCalls);
    }

    public static WorkflowRunState empty() {
        return new WorkflowRunState(Map.of(), List.of(), false);
    }
}
