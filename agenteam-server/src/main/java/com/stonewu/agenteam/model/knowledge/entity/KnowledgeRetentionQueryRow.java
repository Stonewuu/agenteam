package com.stonewu.agenteam.model.knowledge.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * KnowledgeRetentionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class KnowledgeRetentionQueryRow {
    private String enterpriseId;
    private String id;
}
