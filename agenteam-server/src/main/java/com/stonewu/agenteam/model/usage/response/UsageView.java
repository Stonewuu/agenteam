package com.stonewu.agenteam.model.usage.response;

import java.util.List;

/**
 * 各层分别展示实际次数，企业总用量只读取企业自身记录。
 */
public record UsageView(String periodStart, String periodEnd, String timezone, List<Item> items) {
    public record Item(String policyId, String subjectType, String subjectName, long usedCount, long reservedCount,
                       Long monthlyLimit, Long remainingCount) {
    }
}
