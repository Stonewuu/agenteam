package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * run_id 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunApprovalQueryRow {
    private String queryValue1;
    private String runId;
    private String id;
    private String enterpriseId;
    private String stepId;
    private String toolCallId;
    private String approverUserId;
    private String requestHash;
    private String summaryJson;
    private String status;
    private Long revision = 0L;
    private Timestamp expiresAt;
    private Timestamp decidedAt;
}
