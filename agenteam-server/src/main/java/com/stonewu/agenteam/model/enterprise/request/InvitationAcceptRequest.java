package com.stonewu.agenteam.model.enterprise.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 接受邀请的凭据和新账号密码不得出现在日志中。
 */
public record InvitationAcceptRequest(String token, String username, String displayName, String password) {
    @Override
    public String toString() {
        return Messages.log("log.enterprise.invitationAcceptRequest.toString.1");
    }
}
