package com.stonewu.agenteam.model.agent.response;

public record AgentHireView(String id, String revision, String createdAt, String updatedAt, String agentId,
                            String userId, String status, String hiredAt, String lastUsedAt) {
}
