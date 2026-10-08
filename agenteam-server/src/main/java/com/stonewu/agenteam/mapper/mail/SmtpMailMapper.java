package com.stonewu.agenteam.mapper.mail;

import com.stonewu.agenteam.service.auth.AccountInputValidation;
import com.stonewu.agenteam.service.mail.MailDeliveryException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件服务适配器，只返回发送调用是否得到服务确认，不推测用户是否收到或已读。
 */
@Component
public class SmtpMailMapper {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public SmtpMailMapper(ObjectProvider<JavaMailSender> senders, @Value("${agenteam.mail.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    public void send(String recipient, String subject, String content, String messageId) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new MailDeliveryException("MAIL_UNAVAILABLE", "邮件服务暂不可用，请稍后重试。");
        }
        try {
            String address = AccountInputValidation.email(from, "from");
            var message = sender.createMimeMessage();
            message.getSession().setDebug(false);
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(address);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(content, false);
            message.saveChanges();
            message.setHeader("Message-ID",
                "<" + messageId + "@" + address.substring(address.lastIndexOf('@') + 1) + ">");
            sender.send(message);
        } catch (Exception exception) {
            throw new MailDeliveryException("MAIL_SEND_FAILED", "邮件服务未确认发送，请稍后重试。", exception);
        }
    }
}
