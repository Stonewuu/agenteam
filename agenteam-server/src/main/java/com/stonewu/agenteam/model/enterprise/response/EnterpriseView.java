package com.stonewu.agenteam.model.enterprise.response;

/**
 * 企业管理资料；工作空间选择器只使用名称和简介摘要。
 */
public record EnterpriseView(String id, String revision, String createdAt, String updatedAt, String name,
                             String description,
                             String contactEmail, String timezone, String quotaTimezone, String pendingQuotaTimezone,
                             int retentionDays, String status) {
}
