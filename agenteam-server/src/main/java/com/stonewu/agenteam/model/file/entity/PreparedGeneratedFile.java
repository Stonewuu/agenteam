package com.stonewu.agenteam.model.file.entity;

/**
 * 平台已经写完但尚未登记的文件，在最终授权事务中保存归属。
 */
public record PreparedGeneratedFile(String id, String enterpriseId, String ownerUserId, String resourceId,
                                    String resourceVersionId, String runId,
                                    String purpose, String name, String mediaType, String storageKey, long size,
                                    String sha256) {
}
