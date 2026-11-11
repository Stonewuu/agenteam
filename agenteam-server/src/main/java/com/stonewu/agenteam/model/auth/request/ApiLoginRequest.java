package com.stonewu.agenteam.model.auth.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 新版登录使用用户名或已验证邮箱，不接收客户端身份和权限。
 */
public record ApiLoginRequest(String identifier, String password) {
    @Override
    public String toString() {
        return Messages.log("log.auth.apiLoginRequest.toString.1");
    }
}
