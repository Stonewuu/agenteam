package com.stonewu.agenteam.model.schedule.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ScheduleMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ScheduleQueryRow {
    private String actionType;
    private Integer actionSchemaVersion;
    private String actionConfigJson;
    private String enterpriseId;
    private String ownerUserId;
    private String id;
    private String weekdaysJson;
    private String frequency;
    private LocalDate localDate;
    private LocalTime localTime;
    private Integer monthDay;
    private String timezone;
    private String hireId;
    private String agentVersionId;
    private String name;
    private String inputText;
    private Boolean enabled = false;
    private Integer maxRetries = 0;
    private String pauseReason;
    private String activeOccurrenceId;
    private Long revision = 0L;
    private String agentId;
    private String agentName;
    private String agentConfigJson;
    private Integer agentVersionNo;
    private Timestamp nextRunAt;
    private Timestamp lastCheckedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp deletedAt;
}
