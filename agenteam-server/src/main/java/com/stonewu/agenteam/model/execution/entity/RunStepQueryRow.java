package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * RunStepMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunStepQueryRow {
    private String id;
    private String parentStepId;
    private String attemptId;
    private String kind;
    private String title;
    private Integer displayOrder = 0;
    private String status;
    private String publicSummary;
    private String workflowJson;
    private Timestamp startedAt;
    private Timestamp finishedAt;
}
