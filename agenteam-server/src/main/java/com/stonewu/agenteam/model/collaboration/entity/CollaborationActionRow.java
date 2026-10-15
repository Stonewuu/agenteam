package com.stonewu.agenteam.model.collaboration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 与工具结果一起提交的协作事实；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("collaboration_action")
public class CollaborationActionRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("task_id")
    private String taskId;

    @TableField("work_id")
    private String workId;

    @TableField("source_run_id")
    private String sourceRunId;

    @TableField("source_tool_call_id")
    private String sourceToolCallId;

    @TableField("operation_key")
    private String operationKey;

    @TableField("action_type")
    private String actionType;

    @TableField("input_revision")
    private Long inputRevision;

    @TableField("sequence")
    private Long sequence;

    @TableField("arguments_hash")
    private String argumentsHash;

    @TableField("payload_encrypted")
    private String payloadEncrypted;

    @TableField("result_encrypted")
    private String resultEncrypted;
}
