package com.stonewu.agenteam.model.resource.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 发布正文固定保存，只有使用资格可以单独撤销。
 */
public record ResourceVersionRecord(String id, String enterpriseId, String resourceId, int versionNo, String name,
                                    String description, JsonNode config, String configHash, String releaseNote,
                                    String status, String publishedBy, String publishedByName, Instant publishedAt) {
}
