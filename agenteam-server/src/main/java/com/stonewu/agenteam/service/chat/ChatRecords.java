package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;

import java.time.Instant;
import java.util.UUID;

/** 在明确企业范围内初始化新记录，具体业务字段由所属服务设置。 */
public final class ChatRecords {
    private ChatRecords() {
    }

    public static <T extends EnterpriseScopedRecord> T initialize(T row, String enterprise, Instant now) {
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
