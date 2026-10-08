package com.stonewu.agenteam.model.mail.entity;

import com.stonewu.agenteam.model.security.entity.EncryptedPayload;

/**
 * 队列公开部分只保留用途和业务编号，收件地址及凭据都在加密内容中。
 */
public record MailJobPayload(String purpose, String targetId, EncryptedPayload content) {
}
