package com.stonewu.agenteam.model.modelusage.entity;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.background.entity.JobLease;

/**
 * 只由可信执行记录创建的调用归属，每个订阅独立传递，不使用线程局部变量。
 */
public record ModelCallContext(String enterpriseId, String userId, String conversationId, String runId,
                               int attemptNo, long leaseVersion, String agentId, String agentSessionId,
                               String workflowNodeId, String sourceKind, String sourceId, String purpose,
                               BackgroundIdentity backgroundJob) {
    public record BackgroundIdentity(String id, String owner, long version) {
    }

    public ModelCallContext(String enterpriseId, String userId, String conversationId, String runId,
                            int attemptNo, long leaseVersion, String agentId, String agentSessionId,
                            String workflowNodeId, String sourceKind, String sourceId, String purpose) {
        this(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion, agentId, agentSessionId,
            workflowNodeId, sourceKind, sourceId, purpose, null);
    }

    public static ModelCallContext forTitle(JobLease lease, String conversation, String agent) {
        return new ModelCallContext(lease.enterpriseId(), lease.ownerUserId(), conversation, null, 0, 0,
            agent, null, null, "conversation", conversation, "title",
            new BackgroundIdentity(lease.id(), lease.leaseOwner(), lease.leaseVersion()));
    }
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
            agentId, session, workflowNodeId, sourceKind, sourceId, purpose, backgroundJob);
    }

    public ModelCallContext forChild(String resourceId) {
        return new ModelCallContext(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion,
            resourceId, null, workflowNodeId, sourceKind, sourceId, "subagent", backgroundJob);
    }

    public ModelCallContext forCompaction() {
        return new ModelCallContext(enterpriseId, userId, conversationId, runId, attemptNo, leaseVersion,
            agentId, agentSessionId, workflowNodeId, sourceKind, sourceId, "compaction", backgroundJob);
    }
}
