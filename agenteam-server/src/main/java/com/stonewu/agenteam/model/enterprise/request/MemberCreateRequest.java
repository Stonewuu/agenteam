package com.stonewu.agenteam.model.enterprise.request;

import java.util.List;

/**
 * 管理员只能创建普通账号并分配当前企业中自己有权授予的角色。
 */
public record MemberCreateRequest(String username, String password, String displayName, List<String> roleIds,
                                  List<String> teamIds) {
    @Override
    public String toString() {
        return "MemberCreateRequest[账号凭据已隐藏]";
    }
}
