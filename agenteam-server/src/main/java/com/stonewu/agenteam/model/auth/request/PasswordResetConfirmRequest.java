package com.stonewu.agenteam.model.auth.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 凭一次性邮件链接设置新密码。
 */
public record PasswordResetConfirmRequest(String token, String newPassword) {
    @Override
    public String toString() {
        return Messages.log("log.auth.passwordResetConfirmRequest.toString.1");
    }
}
