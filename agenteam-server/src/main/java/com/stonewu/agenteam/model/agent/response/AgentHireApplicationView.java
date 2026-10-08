package com.stonewu.agenteam.model.agent.response;

import com.stonewu.agenteam.model.enterprise.response.ActorView;

import java.util.List;

public record AgentHireApplicationView(String id, String revision, String createdAt, String updatedAt, String agentId,
                                       String agentName,
                                       ActorView applicant, String status, String requestNote, String decisionNote,
                                       String expiresAt, List<String> allowedActions,
                                       String agentIcon, String agentColor) {
}
