package com.stonewu.agenteam.model.agent.entity;

import java.time.Instant;

/**
 * 成员在一个企业内与一个数字员工的唯一使用关系。
 */
public record AgentHire(String id, String enterpriseId, String userId, String agentId, String status, long revision,
                        Instant hiredAt, Instant lastUsedAt, Instant createdAt, Instant updatedAt) {
}
