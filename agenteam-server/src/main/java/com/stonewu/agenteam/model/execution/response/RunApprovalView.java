package com.stonewu.agenteam.model.execution.response;

/**
 * 原发起人确认一次固定操作，正文已经移除认证信息。
 */
public record RunApprovalView(String id, String runId, String stepId, String requestHash, String revision,
                              String status, String expiresAt, Summary summary, String kind) {
    public record Summary(String title, String target, String description, String content, String irreversibleEffect) {
    }
}
