package com.stonewu.agenteam.model.enterprise.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * OrganizationViewMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class OrganizationViewQueryRow {
    private String roleId;
    private String permissionCode;
    private String id;
    private String revision;
    private String name;
    private String description;
    private String contactEmail;
    private String timezone;
    private String quotaTimezone;
    private String pendingQuotaTimezone;
    private Integer retentionDays = 0;
    private String status;
    private String ownerUserId;
    private String ownerName;
    private Integer memberCount = 0;
    private String code;
    private String dataScope;
    private Boolean builtin = false;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
