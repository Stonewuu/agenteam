package com.stonewu.agenteam.model.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 对应 scheduled_task 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("scheduled_task")
public class ScheduledTaskRow {
    @TableField("action_type")
    private String actionType;

    @TableField("action_schema_version")
    private Integer actionSchemaVersion;

    @TableField("action_config_json")
    private String actionConfigJson;

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "owner_user_id")
    private String ownerUserId;

    @TableField(value = "hire_id")
    private String hireId;

    @TableField(value = "agent_version_id")
    private String agentVersionId;

    @TableField(value = "name")
    private String name;

    @TableField(value = "input_text")
    private String inputText;

    @TableField(value = "frequency")
    private String frequency;

    @TableField(value = "local_date")
    private LocalDate localDate;

    @TableField(value = "local_time")
    private String localTime;

    @TableField(value = "weekdays_json")
    private String weekdaysJson;

    @TableField(value = "month_day")
    private Integer monthDay;

    @TableField(value = "timezone")
    private String timezone;

    @TableField(value = "enabled")
    private Integer enabled;

    @TableField(value = "max_retries")
    private Integer maxRetries;

    @TableField(value = "next_run_at")
    private Instant nextRunAt;

    @TableField(value = "last_checked_at")
    private Instant lastCheckedAt;

    @TableField(value = "pause_reason")
    private String pauseReason;

    @TableField(value = "active_occurrence_id")
    private String activeOccurrenceId;

    @TableField(value = "deleted_at")
    private Instant deletedAt;

    @TableField(value = "deleted_token")
    private String deletedToken;

    @TableField(value = "revision")
    private Long revision;

    @TableField(value = "created_at")
    private Instant createdAt;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
