package com.stonewu.agenteam.model.tool.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ToolLogMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ToolLogQueryRow {
    private String requestRedactedJson;
    private String resultRedactedJson;
    private String id;
    private String runId;
    private String actorUserId;
    private String displayName;
    private String toolName;
    private String status;
    private String operationClass;
    private Long durationMs;
    private String errorSummary;
    private Timestamp createdAt;
    private Boolean canViewDetails = false;
    private String runSource;
    private String conversationId;
}
