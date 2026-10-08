package com.stonewu.agenteam.model.knowledge.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * KnowledgeInputOptionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class KnowledgeInputOptionQueryRow {
    private String id;
    private Integer activeGeneration = 0;
    private String name;
    private String resourceId;
    private Timestamp updatedAt;
}
