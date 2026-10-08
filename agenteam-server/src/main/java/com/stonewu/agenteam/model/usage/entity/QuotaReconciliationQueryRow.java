package com.stonewu.agenteam.model.usage.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * QuotaReconciliationMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class QuotaReconciliationQueryRow {
    private String id;
    private Long usedCount = 0L;
    private Long reservedCount = 0L;
    private Long actualUsed = 0L;
    private Long actualReserved = 0L;
}
