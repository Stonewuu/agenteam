package com.stonewu.agenteam.model.modelusage.entity;

import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;

import java.time.Instant;

/**
 * 同一次请求的保存凭证和有限计量数据，不包含模型输入或凭据。
 */
public record ModelUsageSnapshot(String enterpriseId, String callId, String ownerId,
                                 ModelUsageAccumulator.Snapshot usage, String callStatus, Instant capturedAt,
                                 Integer httpStatus, String providerRequestId, String providerErrorCode, String errorCategory) {
    public boolean finished() {
        return !callStatus.equals("in_flight");
    }

    public long sequence() {
        return Math.addExact(Math.multiplyExact(usage.sequence(), 2), finished() ? 1 : 0);
    }
}
