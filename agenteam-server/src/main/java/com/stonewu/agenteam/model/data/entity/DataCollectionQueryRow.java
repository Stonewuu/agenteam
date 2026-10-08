package com.stonewu.agenteam.model.data.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * DataCollectionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class DataCollectionQueryRow {
    private String name;
    private String label;
    private String valueType;
    private Boolean readable = false;
    private Boolean filterable = false;
    private Boolean sortable = false;
    private Boolean sensitive = false;
    private Boolean nullable = false;
    private Integer ordinal = 0;
    private String fileId;
    private String id;
    private String enterpriseId;
    private String resourceId;
    private String sourceName;
    private Integer activeGeneration = 0;
    private Long rowCount;
    private String status;
    private Long revision = 0L;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
