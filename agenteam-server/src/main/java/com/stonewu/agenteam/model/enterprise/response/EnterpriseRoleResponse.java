package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

/**
 * 企业可分配角色及其权限。
 */
public record EnterpriseRoleResponse(
    String roleId,
    String code,
    String name,
    boolean builtin,
    List<String> permissionCodes,
    String description,
    String dataScope,
    String status,
    long revision) {
}
