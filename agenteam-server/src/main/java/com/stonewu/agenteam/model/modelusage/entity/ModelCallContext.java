package com.stonewu.agenteam.model.modelusage.entity;

import com.stonewu.agenteam.model.execution.entity.RunRecord;

/**
 * 只由可信执行记录创建的调用归属，每个订阅独立传递，不使用线程局部变量。
 */
public record ModelCallContext(String enterpriseId, String userId, String conversationId, String runId,
                               int attemptNo, long leaseVersion, String agentId, String agentSessionId,
                               String workflowNodeId, String sourceKind, String sourceId, String purpose) {
    public static ModelCallContext fromRun(RunRecord run, String agentId, String nodeId) {
        String schedule = run.executionConfig().path("scheduleId").asText(null);
        String purpose = run.mode() != null && run.mode().contains("preview") ? "preview"
            : nodeId == null ? "agent" : "workflow";
        return new ModelCallContext(run.enterpriseId(), run.userId(), run.conversationId(), run.id(),
            run.currentAttemptNo(), run.leaseVersion(), agentId, null, nodeId,
            schedule == null ? "conversation" : "schedule", schedule == null ? run.conversationId() : schedule, purpose);
    }

    public ModelCallContext forSession(String session) {
        return new ModelCallContext(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion,
            agentId, session, workflowNodeId, sourceKind, sourceId, purpose);
    }

    public ModelCallContext forChild(String resourceId) {
        return new ModelCallContext(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion,
            resourceId, null, workflowNodeId, sourceKind, sourceId, "subagent");
    }

    public ModelCallContext forCompaction() {
        return new ModelCallContext(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion,
            agentId, agentSessionId, workflowNodeId, sourceKind, sourceId, "compaction");
    }
}
