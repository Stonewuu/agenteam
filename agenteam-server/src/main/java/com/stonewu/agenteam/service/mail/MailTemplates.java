package com.stonewu.agenteam.service.mail;

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
            throw new IllegalArgumentException("邮件站点地址必须是完整且不带路径的站点来源");
        }
        origin = configuredOrigin.endsWith("/") ? configuredOrigin.substring(0,
            configuredOrigin.length() - 1) : configuredOrigin;
    }

    public PreparedMail render(String jobId, String purpose, MailDeliveryContent content) {
        String expires = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm（zzzz）", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of(content.timezone())).format(Instant.parse(content.expiresAt()));
        String subject;
        String introduction;
        String path;
        switch (purpose) {
            case "password_reset" -> {
                subject = "重置账号密码";
                introduction = "你申请了重置账号密码。请打开下方链接设置新密码。";
                path = "/reset-password";
            }
            case "email_verify" -> {
                subject = "验证账号邮箱";
                introduction = "请打开下方链接，确认将此邮箱用于你的账号。";
                path = "/settings/verify-email";
            }
            case "enterprise_invitation" -> {
                subject = "邀请你加入" + content.enterpriseName();
                introduction = content.inviterName() + "邀请你加入「" + content.enterpriseName() + "」。请打开下方链接查看并接受邀请。";
                if (content.note() != null && !content.note().isBlank()) {
                    introduction += "\n\n邀请说明：\n" + content.note();
                }
                path = "/invite";
            }
            default -> throw new IllegalArgumentException("邮件用途未定义");
        }
        String text = introduction + "\n\n" + origin + path + "#token=" + content.token()
            + "\n\n链接有效期至 " + expires + "。\n如果这不是你的操作，请忽略此邮件。";
        return new PreparedMail(content.recipient(), subject, text, jobId);
    }
}
