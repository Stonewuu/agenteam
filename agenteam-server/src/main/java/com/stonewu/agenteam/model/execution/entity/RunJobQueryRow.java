package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * query_value2 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunJobQueryRow {
    private String id;
    private String enterpriseId;
    private String ownerUserId;
    private String runId;
    private String leaseOwner;
    private Long leaseVersion = 0L;
    private String lastEventBatchId;
    private String lastEventBatchHash;
    private Timestamp leaseUntil;
}
