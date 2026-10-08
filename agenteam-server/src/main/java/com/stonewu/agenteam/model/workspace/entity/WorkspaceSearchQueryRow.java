package com.stonewu.agenteam.model.workspace.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * WorkspaceSearchMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class WorkspaceSearchQueryRow {
    private String id;
    private String name;
    private String description;
    private String kind;
    private String icon;
    private String color;
}
