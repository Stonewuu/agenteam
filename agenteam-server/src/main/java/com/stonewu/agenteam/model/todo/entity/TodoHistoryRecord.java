package com.stonewu.agenteam.model.todo.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TodoHistoryRecord(String id, String actorUserId, String actorName, String action, JsonNode before,
                                JsonNode after, Instant createdAt) {
}
