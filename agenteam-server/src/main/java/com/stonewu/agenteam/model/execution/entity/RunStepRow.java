package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 run_step 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("run_step")
public class RunStepRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "attempt_id")
    private String attemptId;

    @TableField(value = "parent_step_id")
    private String parentStepId;

    @TableField(value = "step_key")
    private String stepKey;

    @TableField(value = "kind")
    private String kind;

    @TableField(value = "title")
    private String title;

    @TableField(value = "display_order")
    private Long displayOrder;

    @TableField(value = "status")
    private String status;

    @TableField(value = "input_json")
    private String inputJson;

    @TableField(value = "output_json")
    private String outputJson;

    @TableField(value = "public_summary")
    private String publicSummary;

    @TableField(value = "workflow_json")
    private String workflowJson;

    @TableField(value = "lease_version")
    private Long leaseVersion;

    @TableField(value = "started_at")
    private Instant startedAt;

    @TableField(value = "finished_at")
    private Instant finishedAt;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
