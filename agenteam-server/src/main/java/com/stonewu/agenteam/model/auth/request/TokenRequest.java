package com.stonewu.agenteam.model.auth.request;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 接收一次性邮件凭据，不把原文写入日志。
 */
public record TokenRequest(String token) {
    @Override
    public String toString() {
        return Messages.log("log.auth.tokenRequest.toString.1");
    }
}
