package com.stonewu.agenteam.model.knowledge.response;

/**
 * 公开当前资料状态，修改版本使用字符串避免浏览器精度丢失。
 */
public record KnowledgeDocumentView(String id, String revision, String createdAt, String updatedAt, String name,
                                    String fileId, String activeFileId, int activeGeneration, int pendingGeneration,
                                    String status, int chunkCount,
                                    Integer pageCount, String errorCode, String errorSummary, String processedAt,
                                    long sizeBytes) {
}
