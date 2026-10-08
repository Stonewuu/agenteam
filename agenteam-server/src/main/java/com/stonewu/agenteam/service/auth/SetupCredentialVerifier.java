package com.stonewu.agenteam.service.auth;

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
            throw new IllegalArgumentException("初始化部署凭据长度不符合要求");
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
        log.info("系统尚未初始化，本次启动的初始化凭据：{}", generated);
        log.info("请在初始化页面填写上述凭据；完成初始化前重启后端会生成新凭据。");
    }

    public void verify(String provided) {
        if (expected == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "暂时无法初始化，请联系部署人员。");
        }
        if (provided == null || provided.length() < 16 || provided.length() > 256 || !MessageDigest.isEqual(expected,
            digest(provided))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "初始化凭据不正确。");
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少必要的摘要算法", exception);
        }
    }
}
