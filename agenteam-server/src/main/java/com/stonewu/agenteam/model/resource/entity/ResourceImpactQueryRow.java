package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * ResourceImpactMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ResourceImpactQueryRow {
    private String id;
    private String kind;
    private String name;
}
