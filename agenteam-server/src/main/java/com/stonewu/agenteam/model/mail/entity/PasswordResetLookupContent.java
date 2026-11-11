package com.stonewu.agenteam.model.mail.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 找回请求统一排队后再核对账号，避免通过同步处理差异查询账号是否存在。
 */
public record PasswordResetLookupContent(String identifier, String expiresAt) {
    @Override
    public String toString() {
        return Messages.log("log.mail.passwordResetLookupContent.toString.1");
    }
}
