package com.stonewu.agenteam.model.mail.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 仅在加密队列和内存中流转的投递内容。
 */
public record MailDeliveryContent(String recipient, String token, String enterpriseName, String inviterName,
                                  String timezone, String expiresAt, String note) {
    public MailDeliveryContent(String recipient, String token, String enterpriseName, String inviterName,
                               String timezone, String expiresAt) {
        this(recipient, token, enterpriseName, inviterName, timezone, expiresAt, "");
    }

    @Override
    public String toString() {
        return Messages.log("log.mail.mailDeliveryContent.toString.1");
    }
}
