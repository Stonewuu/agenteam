package com.stonewu.agenteam.model.permission.entity;

/**
 * 已验证的资源访问范围，只携带业务参数，不携带数据库语句。
 */
public record ResourceQueryScope(String enterpriseId, String userId, String kind, String dataScope,
                                 String capability, boolean maintainAll, boolean includeDeleted) {
}
