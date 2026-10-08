package com.stonewu.agenteam.model.execution.entity;

import java.time.Instant;

/**
 * 当前领取资格；版本与到期时间共同阻止旧进程保存结果。
 */
public record JobLease(String id, String enterpriseId, String userId, String runId, String owner,
                       long version, Instant until) {
}
