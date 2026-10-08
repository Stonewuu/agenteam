package com.stonewu.agenteam.model.enterprise.entity;

import java.time.Instant;

/**
 * 企业的持久化结构。
 */
public record EnterpriseEntity(
    String id,
    String name,
    String status,
    Instant createdAt,
    Instant updatedAt) {
}
