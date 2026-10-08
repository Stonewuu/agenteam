package com.stonewu.agenteam.model.resource.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 资源身份与当前草稿；不会代替不可变发布版本。
 */
public record ResourceRecord(String id, String enterpriseId, ResourceKind kind, String name, String description,
                             String subtype, String ownerUserId, String ownerDisplayName, String source, String status,
                             String publishedVersionId, int nextVersionNo, long revision, Instant createdAt,
                             Instant updatedAt,
                             Instant deletedAt, JsonNode config, String configHash, JsonNode validation) {
    public ResourceRecord withConfiguration(JsonNode value, String hash) {
        return new ResourceRecord(id, enterpriseId, kind, name, description, subtype, ownerUserId, ownerDisplayName,
            source, status,
            publishedVersionId, nextVersionNo, revision, createdAt, updatedAt, deletedAt, value, hash, null);
    }
}
