package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ExecutionMessageMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ExecutionMessageQueryRow {
    private String id;
    private String runId;
    private Integer attemptNo = 0;
    private String role;
    private String content;
    private String status;
    private String blocksJson;
    private String feedback;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
