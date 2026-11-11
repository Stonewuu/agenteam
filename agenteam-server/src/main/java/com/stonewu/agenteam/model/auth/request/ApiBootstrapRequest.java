package com.stonewu.agenteam.model.auth.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 首次部署的完整输入，凭据与密码不能出现在日志中。
 */
public record ApiBootstrapRequest(String setupCredential, String username, String displayName, String password,
                                  String enterpriseName, String email, String timezone) {
    @Override
    public String toString() {
        return Messages.log("log.auth.apiBootstrapRequest.toString.1");
    }
}
