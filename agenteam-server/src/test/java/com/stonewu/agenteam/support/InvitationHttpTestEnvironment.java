package com.stonewu.agenteam.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;

/**
 * 邀请请求使用单独的随机库、会话命名空间和仅本机邮件服务。
 */
public final class InvitationHttpTestEnvironment implements AutoCloseable {
    private final IsolatedDatabase database;
    private final GreenMail mailbox;

    public InvitationHttpTestEnvironment() {
        try {
            database = new IsolatedDatabase();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法建立邀请测试数据库", exception);
        }
        mailbox = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        mailbox.start();
    }

    public GreenMail mailbox() {
        return mailbox;
    }

    public void properties(DynamicPropertyRegistry registry) {
        String url;
        try (var connection = database.connection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取邀请测试连接", exception);
        }
        var mysql = IsolatedInfrastructure.mysql();
        var redis = IsolatedInfrastructure.redis();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> 1);
        registry.add("spring.session.redis.namespace", () -> "agenteam:test:invitations");
        registry.add("agenteam.auth.setup-credential", () -> "isolated-invitation-setup-credential");
        registry.add("agenteam.web.public-base-url", () -> "http://localhost:3000");
        registry.add("agenteam.mail.worker-enabled", () -> "false");
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> mailbox.getSmtp().getPort());
        registry.add("spring.mail.username", () -> "");
        registry.add("spring.mail.password", () -> "");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "1000");
        registry.add("spring.mail.properties.mail.smtp.timeout", () -> "1000");
        registry.add("spring.mail.properties.mail.smtp.writetimeout", () -> "1000");
        registry.add("agenteam.mail.from", () -> "agenteam@example.test");
        registry.add("logging.level.com.icegreen.greenmail.user.UserManager", () -> "WARN");
        String signingKey = key();
        String encryptionKey = key();
        registry.add("agenteam.security.request-signing-key", () -> signingKey);
        registry.add("agenteam.security.encryption-keys", () -> "{\"1\":\"" + encryptionKey + "\"}");
    }

    private String key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Override
    public void close() throws SQLException {
        mailbox.stop();
        database.close();
    }
}
