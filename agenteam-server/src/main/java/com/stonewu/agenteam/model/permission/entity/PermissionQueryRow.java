package com.stonewu.agenteam.model.permission.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * PermissionMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class PermissionQueryRow {
    private String id;
    private String code;
    private String name;
    private Boolean builtin = false;
    private String description;
    private String dataScope;
    private String status;
    private Long revision = 0L;
    private String scope;
    private String menuKey;
    private String menuLabel;
    private String menuPath;
    private Integer sortNo = 0;
}
