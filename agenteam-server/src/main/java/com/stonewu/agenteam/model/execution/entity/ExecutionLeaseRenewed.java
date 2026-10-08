package com.stonewu.agenteam.model.execution.entity;

import java.time.Instant;

/**
 * 数据库已经确认的执行资格和有效期，用于更新 Redis 的写入限制。
 */
public record ExecutionLeaseRenewed(RunRecord run, JobLease lease, Instant until) {
}
