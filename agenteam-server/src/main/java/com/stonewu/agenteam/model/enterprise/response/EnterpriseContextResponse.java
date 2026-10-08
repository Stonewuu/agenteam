package com.stonewu.agenteam.model.enterprise.response;

import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse.EnterpriseChoice;

import java.util.List;

/**
 * 页面路径所指定企业的当前成员、操作权限与实际入口。
 */
public record EnterpriseContextResponse(EnterpriseChoice enterprise, MemberView member, List<String> permissions,
                                        String permissionVersion, List<Menu> menus, List<String> capabilities) {
    public record Menu(String key, String label, String area, String path) {
    }
}
