package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ResourceSubjectMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ResourceSubjectQueryRow {
    private String id;
    private String name;
    private Boolean active = false;
    private Timestamp createdAt;
}
