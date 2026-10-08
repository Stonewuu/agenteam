package com.stonewu.agenteam.model.memory.response;

public record MemoryView(String id, String revision, String createdAt, String updatedAt,
                         String agentId, String memoryKey, String content, String expiresAt) {
}
