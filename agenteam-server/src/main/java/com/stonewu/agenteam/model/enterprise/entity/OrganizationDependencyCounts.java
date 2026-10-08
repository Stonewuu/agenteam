package com.stonewu.agenteam.model.enterprise.entity;

/**
 * 同一时刻读取组织成员、邀请和权限相关的实际引用数量。
 */
public record OrganizationDependencyCounts(long members, long pendingInvitations, long quotaPolicies,
                                           long resourceGrants) {
}
