package com.stonewu.agenteam.model.user.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 申请新邮箱验证，确认完成前保留旧邮箱。
 */
public record EmailChangeRequest(String newEmail, String currentPassword) {
    @Override
    public String toString() {
        return Messages.log("log.user.emailChangeRequest.toString.1");
    }
}
