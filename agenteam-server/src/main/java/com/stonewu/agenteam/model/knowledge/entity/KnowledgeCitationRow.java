package com.stonewu.agenteam.model.knowledge.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 引用需要的文档身份、原始位置和公开正文。
 */
@Getter
@Setter
public class KnowledgeCitationRow {
    private String chunkId;
    private String documentId;
    private String resourceId;
    private String name;
    private String fileId;
    private Integer generation;
    private String locatorJson;
    private String contentText;
}
