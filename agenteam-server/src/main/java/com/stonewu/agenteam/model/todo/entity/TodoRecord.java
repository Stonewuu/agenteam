package com.stonewu.agenteam.model.todo.entity;

import java.time.Instant;
import java.time.LocalDate;

public record TodoRecord(String id, String enterpriseId, String title, String description, String createdBy,
                         String ownerUserId,
                         String teamId, LocalDate dueDate, String priority, TodoStatus status, TodoSource source,
                         Instant completedAt, Instant deletedAt, long revision, Instant createdAt, Instant updatedAt,
                         String creatorName, String ownerName, String teamName) {
}
