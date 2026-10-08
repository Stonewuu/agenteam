package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * EnterpriseMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class EnterpriseQueryRow {
    private String userId;
    private String username;
    private String displayName;
    private String status;
    private Long revision = 0L;
    private String id;
    private String name;
    private String description;
    private String ownerUserId;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
