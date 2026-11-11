package com.stonewu.agenteam.service.mail;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.mail.entity.MailDeliveryContent;
import com.stonewu.agenteam.model.mail.entity.PreparedMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 链接只从固定站点地址生成，邮件明确显示实际失效时间。
 */
@Component
public class MailTemplates {
    private final String origin;

    public MailTemplates(@Value("${agenteam.web.public-base-url:http://localhost:3000}") String configuredOrigin) {
        URI uri = URI.create(configuredOrigin);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(
            uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException(Messages.text("error.mail.mailTemplates.init.1"));
        }
        origin = configuredOrigin.endsWith("/") ? configuredOrigin.substring(0,
            configuredOrigin.length() - 1) : configuredOrigin;
    }

    public PreparedMail render(String jobId, String purpose, MailDeliveryContent content) {
        String expires = DateTimeFormatter.ofPattern(Messages.source("text.mail.mailTemplates.render.1"), Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of(content.timezone())).format(Instant.parse(content.expiresAt()));
        String subject;
        String introduction;
        String path;
        switch (purpose) {
            case "password_reset" -> {
                subject = Messages.source("text.mail.mailTemplates.render.2");
                introduction = Messages.source("text.mail.mailTemplates.render.3");
                path = "/reset-password";
            }
            case "email_verify" -> {
                subject = Messages.source("text.mail.mailTemplates.render.4");
                introduction = Messages.source("text.mail.mailTemplates.render.5");
                path = "/settings/verify-email";
            }
            case "enterprise_invitation" -> {
                subject = Messages.source("text.mail.mailTemplates.render.6", content.enterpriseName());
                introduction = Messages.source("text.mail.mailTemplates.render.7", content.inviterName(), content.enterpriseName());
                if (content.note() != null && !content.note().isBlank()) {
                    introduction += Messages.source("text.mail.mailTemplates.render.8", content.note());
                }
                path = "/invite";
            }
            default -> throw new IllegalArgumentException(Messages.text("error.mail.mailTemplates.render.1"));
        }
        String text = Messages.source("text.mail.mailTemplates.render.9", introduction, origin, path, content.token(), expires);
        return new PreparedMail(content.recipient(), subject, text, jobId);
    }
}
