package com.stonewu.agenteam.model.auth.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 新生成的单次凭据，原始值只能交给短期加密邮件内容。
 */
public record IssuedToken(String id, String value, String hash) {
    @Override
    public String toString() {
        return Messages.log("log.auth.issuedToken.toString.1");
    }
}
