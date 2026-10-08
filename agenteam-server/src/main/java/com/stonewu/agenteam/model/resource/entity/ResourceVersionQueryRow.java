package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * ResourceVersionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ResourceVersionQueryRow {
    private String dependencyVersionId;
    private String dependencyKind;
    private String bindingKey;
    private Integer ordinal = 0;
    private String id;
    private String enterpriseId;
    private String resourceId;
    private Integer versionNo = 0;
    private String name;
    private String description;
    private String configJson;
    private String configHash;
    private String releaseNote;
    private String status;
    private String publishedBy;
    private String publishedByName;
    private Instant publishedAt;
}
