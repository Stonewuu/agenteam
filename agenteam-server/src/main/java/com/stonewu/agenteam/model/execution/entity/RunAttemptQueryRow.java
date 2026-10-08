package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * RunAttemptMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunAttemptQueryRow {
    private String id;
    private Integer attemptNo = 0;
    private String outputMessageId;
    private String status;
    private String errorSummary;
    private Timestamp startedAt;
    private Timestamp finishedAt;
}
