package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * id 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class PreviewRetentionQueryRow {
    private String enterpriseId;
    private String userId;
    private String id;
}
