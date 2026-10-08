package com.stonewu.agenteam.model.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 knowledge_document 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocumentRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "active_file_id")
    private String activeFileId;

    @TableField(value = "active_generation")
    private Integer activeGeneration;

    @TableField(value = "pending_generation")
    private Integer pendingGeneration;

    @TableField(value = "last_generation")
    private Integer lastGeneration;

    @TableField(value = "status")
    private String status;

    @TableField(value = "chunk_count")
    private Integer chunkCount;

    @TableField(value = "page_count")
    private Integer pageCount;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "error_summary")
    private String errorSummary;

    @TableField(value = "processed_at")
    private Instant processedAt;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
