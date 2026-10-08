package com.stonewu.agenteam.model.permission.entity;

/**
 * 所有者范围查询所需的企业、操作者及已验证的数据范围。
 */
public record OwnerQueryScope(String enterpriseId, String userId, String dataScope) {
}
