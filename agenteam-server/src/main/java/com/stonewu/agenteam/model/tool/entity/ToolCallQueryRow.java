package com.stonewu.agenteam.model.tool.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ToolCallMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ToolCallQueryRow {
    private String id;
    private String enterpriseId;
    private String runId;
    private String stepId;
    private String actorUserId;
    private String resourceId;
    private String resourceKind;
    private String resourceVersionId;
    private Long draftRevision;
    private String pluginToolId;
    private String toolName;
    private String operationId;
    private String frameworkCallId;
    private String frameworkSessionId;
    private String operationClass;
    private String status;
    private String argumentHash;
    private String requestHash;
    private String requestEncryptedJson;
    private String resultEncryptedJson;
    private String requestRedactedJson;
    private String resultRedactedJson;
    private Long leaseVersion = 0L;
    private Integer attemptCount = 0;
    private Integer queryCount = 0;
    private Long durationMs;
    private String errorCode;
    private String errorSummary;
    private Timestamp lastQueryAt;
    private Timestamp submittedAt;
    private Timestamp startedAt;
    private Timestamp finishedAt;
    private Timestamp createdAt;
}
