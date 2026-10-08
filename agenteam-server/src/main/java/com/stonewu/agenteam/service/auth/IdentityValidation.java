package com.stonewu.agenteam.service.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * 身份输入的固定首版规则，长度按 Unicode 字符计算，不要求固定字符组合。
 */
public final class IdentityValidation {

    private static final Set<String> COMMON_PASSWORDS = Set.of(
        "123456789012", "1234567890123", "12345678901234", "123456789012345", "1234567890123456",
        "012345678901", "0123456789012", "password1234", "password12345", "password123456",
        "password123456!", "password123!", "password1234!", "administrator", "administrator123",
        "qwertyuiop123", "qwerty123456", "qwertyuiopasdf", "abc123abc123abc123", "welcome123456",
        "changeme12345", "letmein123456", "admin12345678", "agenteam", "agenteam123");

    private IdentityValidation() {
    }

    public static String username(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[A-Za-z0-9._-]{3,64}")) {
            throw invalid("用户名需要 3～64 个字符，只能包含字母、数字、点、下划线和短横线");
        }
        if (name.equalsIgnoreCase("test")) {
            throw invalid("此用户名已保留，请使用其他用户名");
        }
        return name;
    }

    public static String loginIdentifier(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 254) {
            throw invalid("请输入有效账号");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static String displayName(String value) {
        String name = value == null ? "" : value.trim();
        validateUnicode(name);
        if (name.isBlank() || name.codePointCount(0, name.length()) > 50) {
            throw invalid("显示名需要 1～50 个字符");
        }
        return name;
    }

    public static void newPassword(String value) {
        if (value == null) {
            throw invalid("请输入密码");
        }
        validateUnicode(value);
        int length = value.codePointCount(0, value.length());
        if (length < 12 || length > 128) {
            throw invalid("密码需要 12～128 个字符");
        }
        if (value.isBlank() || value.codePoints().distinct().limit(2).count() < 2
            || COMMON_PASSWORDS.contains(value.trim().toLowerCase(Locale.ROOT))) {
            throw invalid("这个密码过于常见，请换用更长且不易猜测的密码");
        }
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid("输入包含无效字符");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid("输入包含无效字符");
            }
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
