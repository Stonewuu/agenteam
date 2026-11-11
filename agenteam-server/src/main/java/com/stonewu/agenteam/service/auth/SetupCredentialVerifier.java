package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 优先使用配置凭据；首次启动未配置时生成随机值，只在启动日志中显示一次。
 */
@Component
public class SetupCredentialVerifier implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SetupCredentialVerifier.class);
    private final AuthMapper users;
    private volatile byte[] expected;

    public SetupCredentialVerifier(
        @Value("${agenteam.auth.setup-credential:${AGENTEAM_SETUP_CREDENTIAL:}}") String credential,
        AuthMapper users) {
        this.users = users;
        if (!credential.isBlank() && (credential.length() < 16 || credential.length() > 256)) {
            throw new IllegalArgumentException(Messages.text("error.auth.setupCredentialVerifier.init.1"));
        }
        expected = credential.isBlank() ? null : digest(credential);
    }

    @Override
    public synchronized void run(ApplicationArguments arguments) {
        if (expected != null || users.superAdminInitialized()) {
            return;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        expected = digest(generated);
        log.info(Messages.log("log.auth.setupCredentialVerifier.run.1"), generated);
        log.info(Messages.log("log.auth.setupCredentialVerifier.run.2"));
    }

    public void verify(String provided) {
        if (expected == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                Messages.text("error.auth.setupCredentialVerifier.verify.1"));
        }
        if (provided == null || provided.length() < 16 || provided.length() > 256 || !MessageDigest.isEqual(expected,
            digest(provided))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", Messages.text("error.auth.setupCredentialVerifier.verify.2"));
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(Messages.text("error.auth.passwordResetSourceMapper.check.2"), exception);
        }
    }
}
