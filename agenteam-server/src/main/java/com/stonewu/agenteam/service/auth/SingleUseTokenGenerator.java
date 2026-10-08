package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.model.auth.entity.IssuedToken;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 生成三十二字节安全随机凭据，验证表只保存不可逆摘要。
 */
@Component
public class SingleUseTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public IssuedToken issue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(UUID.randomUUID().toString(), value, hash(value));
    }

    public String hash(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            return "";
        }
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少必要的摘要算法", exception);
        }
    }
}
