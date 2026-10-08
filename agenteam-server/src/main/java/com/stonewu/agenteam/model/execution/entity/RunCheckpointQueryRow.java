package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * RunCheckpointMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunCheckpointQueryRow {
    private String stateJson;
    private String stateHash;
    private Long leaseVersion = 0L;
    private String frameworkStateKey;
}
