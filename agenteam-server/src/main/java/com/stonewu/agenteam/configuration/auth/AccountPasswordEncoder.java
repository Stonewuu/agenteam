package com.stonewu.agenteam.configuration.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 账号密码统一使用 Argon2id（带随机盐的单向密码散列算法）。
 */
public final class AccountPasswordEncoder implements PasswordEncoder {

    private final Argon2PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    @Override
    public String encode(CharSequence rawPassword) {
        return argon.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || !encodedPassword.startsWith("$argon2id$v=19$m=")) {
            return false;
        }
        try {
            return argon.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            // 损坏的散列不能通过验证，也不向日志输出密码或散列正文。
            return false;
        }
    }
}
