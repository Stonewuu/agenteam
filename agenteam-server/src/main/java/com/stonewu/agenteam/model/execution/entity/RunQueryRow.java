package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * RunMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunQueryRow {
    private String id;
    private String enterpriseId;
    private String conversationId;
    private String userId;
    private String inputMessageId;
    private String outputMessageId;
    private String agentVersionId;
    private String mode;
    private String status;
    private String executionConfigJson;
    private Integer currentAttemptNo = 0;
    private Integer maxAttempts = 0;
    private Long leaseVersion = 0L;
    private Boolean hasStepErrors = false;
    private Long lastSequence = 0L;
    private String errorCode;
    private String errorMessage;
    private Timestamp startedAt;
    private Timestamp finishedAt;
    private Timestamp cancelRequestedAt;
    private Timestamp nextAttemptAt;
    private Timestamp createdAt;
}
