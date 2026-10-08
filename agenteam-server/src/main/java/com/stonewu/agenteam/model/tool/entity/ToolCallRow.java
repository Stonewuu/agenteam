package com.stonewu.agenteam.model.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 tool_call 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("tool_call")
public class ToolCallRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "attempt_id")
    private String attemptId;

    @TableField(value = "step_id")
    private String stepId;

    @TableField(value = "actor_user_id")
    private String actorUserId;

    @TableField(value = "resource_id")
    private String resourceId;

    @TableField(value = "resource_kind")
    private String resourceKind;

    @TableField(value = "resource_version_id")
    private String resourceVersionId;

    @TableField(value = "draft_revision")
    private Long draftRevision;

    @TableField(value = "tool_name")
    private String toolName;

    @TableField(value = "operation_id")
    private String operationId;

    @TableField(value = "plugin_tool_id")
    private String pluginToolId;

    @TableField(value = "framework_call_id")
    private String frameworkCallId;

    @TableField(value = "framework_session_id")
    private String frameworkSessionId;

    @TableField(value = "argument_hash")
    private String argumentHash;

    @TableField(value = "request_encrypted_json")
    private String requestEncryptedJson;

    @TableField(value = "result_encrypted_json")
    private String resultEncryptedJson;

    @TableField(value = "lease_version")
    private Long leaseVersion;

    @TableField(value = "submitted_at")
    private Instant submittedAt;

    @TableField(value = "operation_class")
    private String operationClass;

    @TableField(value = "status")
    private String status;

    @TableField(value = "request_hash")
    private String requestHash;

    @TableField(value = "request_redacted_json")
    private String requestRedactedJson;

    @TableField(value = "result_redacted_json")
    private String resultRedactedJson;

    @TableField(value = "attempt_count")
    private Integer attemptCount;

    @TableField(value = "query_count")
    private Integer queryCount;

    @TableField(value = "last_query_at")
    private Instant lastQueryAt;

    @TableField(value = "error_code")
    private String errorCode;

    @TableField(value = "error_summary")
    private String errorSummary;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "duration_ms")
    private Long durationMs;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
