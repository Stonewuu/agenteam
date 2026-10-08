package com.stonewu.agenteam.model.mail.entity;

/**
 * 经过当前状态检查、可交给邮件服务的一封邮件。
 */
public record PreparedMail(String recipient, String subject, String text, String messageId) {
    @Override
    public String toString() {
        return "PreparedMail[邮件内容已隐藏]";
    }
}
