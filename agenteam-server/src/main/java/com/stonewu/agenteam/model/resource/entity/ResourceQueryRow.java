package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ResourceMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ResourceQueryRow {
    private Timestamp deletedAt;
    private String id;
    private String enterpriseId;
    private String kind;
    private String name;
    private String description;
    private String subtype;
    private String ownerUserId;
    private String ownerDisplayName;
    private String source;
    private String status;
    private String publishedVersionId;
    private Integer nextVersionNo = 0;
    private Long revision = 0L;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String configJson;
    private String configHash;
    private String validationJson;
}
