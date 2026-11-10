package com.stonewu.agenteam.model.collaboration.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties("leadAgentId")
public record WebRoomCreateRequest(String title, List<String> agentIds) {
}
