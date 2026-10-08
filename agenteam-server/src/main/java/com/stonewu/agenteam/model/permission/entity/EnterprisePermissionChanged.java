package com.stonewu.agenteam.model.permission.entity;

/**
 * 当前事务已修改企业内的授权或有效成员，需要立即检查正在执行的任务。
 */
public record EnterprisePermissionChanged(String enterpriseId) {
}
