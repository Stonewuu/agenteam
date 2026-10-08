package com.stonewu.agenteam.model.execution.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * RunActivityMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class RunActivityQueryRow {
    private String countedToolsJson;
    private Integer usedSteps = 0;
    private Long activeMillis = 0L;
    private String executionPhase;
    private Timestamp activeSegmentStartedAt;
    private Timestamp queuedAt;
}
