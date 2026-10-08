package com.stonewu.agenteam.model.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 knowledge_chunk 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("knowledge_chunk")
public class KnowledgeChunkRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "document_id")
    private String documentId;

    @TableField(value = "file_id")
    private String fileId;

    @TableField(value = "generation")
    private Integer generation;

    @TableField(value = "ordinal")
    private Integer ordinal;

    @TableField(value = "locator_json")
    private String locatorJson;

    @TableField(value = "content_text")
    private String contentText;

    @TableField(value = "search_terms")
    private String searchTerms;

    @TableField(value = "content_hash")
    private String contentHash;

    @TableField(value = "created_at")
    private Instant createdAt;
}
