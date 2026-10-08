package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * UsableVersionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class UsableVersionQueryRow {
    private String resourceId;
    private String versionId;
    private Integer versionNo = 0;
    private String kind;
    private String name;
    private String description;
    private String icon;
    private String color;
    private Timestamp publishedAt;
}
