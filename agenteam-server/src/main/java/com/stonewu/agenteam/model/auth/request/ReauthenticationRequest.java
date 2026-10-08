package com.stonewu.agenteam.model.auth.request;

/** 验证当前账号密码，密码不参与日志输出。 */
public record ReauthenticationRequest(String password) {
    @Override
    public String toString() {
        return "ReauthenticationRequest[密码已隐藏]";
    }
}
