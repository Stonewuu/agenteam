package com.stonewu.agenteam.model.knowledge.entity;

import java.time.Instant;

/**
 * 当前可读处理版本与正在处理的版本分别保存。
 */
public record KnowledgeDocumentRecord(String id, String enterpriseId, String resourceId, String name, String fileId,
                                      String activeFileId, int activeGeneration, int pendingGeneration,
                                      int lastGeneration, String status,
                                      int chunkCount, Integer pageCount, String errorCode, String errorSummary,
                                      Instant processedAt,
                                      Instant deletedAt, long revision, Instant createdAt, Instant updatedAt,
                                      long sizeBytes) {
}
