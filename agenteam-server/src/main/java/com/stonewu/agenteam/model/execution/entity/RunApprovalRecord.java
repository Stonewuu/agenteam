package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.execution.response.RunApprovalView;

import java.time.Instant;

/**
 * 审批只能由固定的原发起成员处理。
 */
public record RunApprovalRecord(String id, String enterpriseId, String runId, String stepId, String toolCallId,
                                String approverUserId, String requestHash, RunApprovalView.Summary summary,
                                String status, Instant expiresAt, Instant decidedAt, long revision) {
    public RunApprovalView view() {
        return new RunApprovalView(id, runId, stepId, requestHash, Long.toString(revision), status,
            expiresAt.toString(), summary,
            toolCallId == null ? "workflow" : "tool");
    }
}
