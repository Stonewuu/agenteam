package com.stonewu.agenteam.model.mail.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 经过当前状态检查、可交给邮件服务的一封邮件。
 */
public record PreparedMail(String recipient, String subject, String text, String messageId) {
    @Override
    public String toString() {
        return Messages.log("log.mail.preparedMail.toString.1");
    }
}
