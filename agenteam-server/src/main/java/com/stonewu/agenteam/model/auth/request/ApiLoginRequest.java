package com.stonewu.agenteam.model.auth.request;

/**
 * 新版登录使用用户名或已验证邮箱，不接收客户端身份和权限。
 */
public record ApiLoginRequest(String identifier, String password) {
    @Override
    public String toString() {
        return "ApiLoginRequest[登录凭据已隐藏]";
    }
}
