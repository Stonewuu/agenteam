package com.stonewu.agenteam.model.user.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 密码修改需要再次核验当前密码；记录文本隐藏两次输入。
 */
public record PasswordChangeRequest(String currentPassword, String newPassword) {
    @Override
    public String toString() {
        return Messages.log("log.user.passwordChangeRequest.toString.1");
    }
}
