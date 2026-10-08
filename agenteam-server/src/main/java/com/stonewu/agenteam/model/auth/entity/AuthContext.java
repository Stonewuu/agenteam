package com.stonewu.agenteam.model.auth.entity;

import com.stonewu.agenteam.model.user.entity.UserEntity;

import java.util.Set;

/**
 * 当前请求经过会话校验后的用户和企业身份。
 */
public record AuthContext(
    UserEntity user,
    String enterpriseId,
    Set<String> permissions) {

    public boolean superAdmin() {
        return user.superAdmin();
    }

    public String userId() {
        return user.id();
    }
}
