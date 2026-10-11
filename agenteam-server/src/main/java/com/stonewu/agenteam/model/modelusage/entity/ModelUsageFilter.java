package com.stonewu.agenteam.model.modelusage.entity;

import java.time.Instant;

/** 查询范围由服务根据当前权限构造，不能直接绑定客户端企业或所有者。 */
public record ModelUsageFilter(String enterpriseId, Instant from, Instant to, String timezone,
                               String modelProfileId, String userId, String conversationId, String runId) {
}
