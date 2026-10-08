package com.stonewu.agenteam.model.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * 用户账户的持久化结构。
 */
public record UserEntity(
    String id,
    String username,
    @JsonIgnore String passwordHash,
    String displayName,
    String status,
    boolean superAdmin,
    String lastEnterpriseId,
    String email,
    Instant emailVerifiedAt,
    long sessionVersion,
    Instant passwordChangedAt,
    long revision,
    Instant createdAt,
    Instant updatedAt) {

    @Override
    public String toString() {
        return "UserEntity[id=" + id + ", status=" + status + "]";
    }
}
