package com.stonewu.agenteam.model.execution.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 run_checkpoint 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("run_checkpoint")
public class RunCheckpointRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "run_id")
    private String runId;

    @TableField(value = "checkpoint_no")
    private Long checkpointNo;

    @TableField(value = "lease_version")
    private Long leaseVersion;

    @TableField(value = "state_json")
    private String stateJson;

    @TableField(value = "framework_state_key")
    private String frameworkStateKey;

    @TableField(value = "state_hash")
    private String stateHash;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
