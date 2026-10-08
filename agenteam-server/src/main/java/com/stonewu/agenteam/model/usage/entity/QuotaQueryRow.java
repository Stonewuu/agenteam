package com.stonewu.agenteam.model.usage.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * state 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class QuotaQueryRow {
    private String quotaTimezone;
    private String pendingQuotaTimezone;
    private String id;
    private String subjectType;
    private String subjectId;
    private Long monthlyLimit;
    private Long usedCount = 0L;
    private Long reservedCount = 0L;
    private String bucketId;
    private String state;
    private Timestamp quotaPeriodStart;
    private Timestamp quotaPeriodEnd;
}
