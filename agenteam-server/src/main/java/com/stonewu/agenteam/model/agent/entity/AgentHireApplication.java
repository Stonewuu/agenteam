package com.stonewu.agenteam.model.agent.entity;

import java.time.Instant;

/**
 * 一次有截止时间的雇佣申请，终态不再接受另一项决定。
 */
public record AgentHireApplication(String id, String enterpriseId, String userId, String applicantName, String agentId,
                                   String agentName,
                                   String status, String requestNote, String decisionNote, long revision,
                                   Instant expiresAt, Instant createdAt, Instant updatedAt, String agentIcon,
                                   String agentColor) {
}
