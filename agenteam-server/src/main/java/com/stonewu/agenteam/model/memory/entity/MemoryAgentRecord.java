package com.stonewu.agenteam.model.memory.entity;

import java.time.Instant;

public record MemoryAgentRecord(String agentId, String agentName, long memoryCount, Instant updatedAt, String agentIcon,
                                String agentColor) {
}
