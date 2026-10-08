package com.stonewu.agenteam.model.enterprise.response;

/**
 * RBAC 权限和对应菜单信息。
 */
public record PermissionResponse(
    String code,
    String name,
    String scope,
    String menuKey,
    String menuLabel,
    String menuPath,
    int sortNo) {
}
