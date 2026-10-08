package com.stonewu.agenteam.model.background.entity;

/**
 * 一次领取的工作与版本；旧工作进程不得用过期版本提交结果。
 */
public record JobLease(String id, String enterpriseId, String ownerUserId, String kind, String payloadJson,
                       int attemptCount, int maxAttempts, String leaseOwner, long leaseVersion, boolean exhausted) {
    @Override
    public String toString() {
        return "JobLease[id=" + id + ", kind=" + kind + ", leaseVersion=" + leaseVersion + "]";
    }
}
