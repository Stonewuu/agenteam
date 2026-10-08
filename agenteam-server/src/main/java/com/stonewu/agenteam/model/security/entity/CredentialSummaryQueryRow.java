package com.stonewu.agenteam.model.security.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * CredentialSummaryMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class CredentialSummaryQueryRow {
    private String id;
    private Long revision = 0L;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String name;
    private String kind;
    private String status;
    private Long referenceCount = 0L;
}
