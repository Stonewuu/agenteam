package com.stonewu.agenteam.model.auth.request;

import com.stonewu.agenteam.service.i18n.Messages;

/** 验证当前账号密码，密码不参与日志输出。 */
public record ReauthenticationRequest(String password) {
    @Override
    public String toString() {
        return Messages.log("log.auth.reauthenticationRequest.toString.1");
    }
}
