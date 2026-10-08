package com.stonewu.agenteam.model.workflow.entity;

import io.agentscope.core.state.State;

import java.util.Map;

/**
 * 一次工作流调用的完整进度；输入输出保存为 JSON（结构化数据）正文，由加密状态存储持久保存。
 */
public record WorkflowInvocationState(String id, String versionId, String parentSessionId, String toolCallId,
                                      String input, String status, Map<String, NodeProgress> nodes, String errorCode,
                                      String errorMessage) implements State {
    public WorkflowInvocationState {
        nodes = Map.copyOf(nodes);
    }

    public record NodeProgress(String status, String input, String output, String branch, int attempts,
                               Long startedAt, Long finishedAt, long activeMillis, Long activeSince, String errorCode,
                               String errorMessage, Long retryAt) {
        public static NodeProgress pending() {
            return new NodeProgress("pending", null, null, null, 0, null, null, 0, null, null, null, null);
        }

        public long elapsed(long now) {
            return activeMillis + (activeSince == null ? 0 : Math.max(0, now - activeSince));
        }

        public boolean terminal() {
            return status.equals("completed") || status.equals("failed") || status.equals("cancelled") || status.equals(
                "skipped");
        }
    }
}
