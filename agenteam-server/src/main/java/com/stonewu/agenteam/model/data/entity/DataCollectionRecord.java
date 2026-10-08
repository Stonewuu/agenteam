package com.stonewu.agenteam.model.data.entity;

import java.time.Instant;

/**
 * 集合身份及当前可查询版本；文件来源还按各版本单独保留。
 */
public record DataCollectionRecord(String id, String enterpriseId, String resourceId, String name, String sourceName,
                                   int activeGeneration, String fileId, Long rowCount, String status, long revision,
                                   Instant createdAt, Instant updatedAt) {
}
