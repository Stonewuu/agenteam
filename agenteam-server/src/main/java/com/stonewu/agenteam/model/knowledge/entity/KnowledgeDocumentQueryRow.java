package com.stonewu.agenteam.model.knowledge.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * KnowledgeDocumentMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class KnowledgeDocumentQueryRow {
    private String id;
    private String enterpriseId;
    private String resourceId;
    private String name;
    private String fileId;
    private String activeFileId;
    private Integer activeGeneration = 0;
    private Integer pendingGeneration = 0;
    private Integer lastGeneration = 0;
    private String status;
    private Integer chunkCount = 0;
    private Integer pageCount;
    private String errorCode;
    private String errorSummary;
    private Long revision = 0L;
    private Long fileSizeBytes = 0L;
    private Timestamp processedAt;
    private Timestamp deletedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
