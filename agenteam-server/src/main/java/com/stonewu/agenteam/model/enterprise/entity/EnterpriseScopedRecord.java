package com.stonewu.agenteam.model.enterprise.entity;

import java.time.Instant;

/** 新业务记录共同的企业、编号、时间与版本字段，不提供跨企业查询能力。 */
public interface EnterpriseScopedRecord {
    String getId();
    void setId(String value);
    String getEnterpriseId();
    void setEnterpriseId(String value);
    Long getRevision();
    void setRevision(Long value);
    Instant getCreatedAt();
    void setCreatedAt(Instant value);
    Instant getUpdatedAt();
    void setUpdatedAt(Instant value);
}
