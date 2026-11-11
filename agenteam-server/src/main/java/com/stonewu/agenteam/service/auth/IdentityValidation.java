package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw invalid(Messages.text("error.auth.identityValidation.username.1"));
        }
        if (name.equalsIgnoreCase("test")) {
            throw invalid(Messages.text("error.auth.identityValidation.username.2"));
        }
        return name;
    }

    public static String loginIdentifier(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 254) {
            throw invalid(Messages.text("error.auth.identityValidation.loginIdentifier.1"));
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static String displayName(String value) {
        String name = value == null ? "" : value.trim();
        validateUnicode(name);
        if (name.isBlank() || name.codePointCount(0, name.length()) > 50) {
            throw invalid(Messages.text("error.auth.identityValidation.displayName.1"));
        }
        return name;
    }

    public static void newPassword(String value) {
        if (value == null) {
            throw invalid(Messages.text("error.auth.identityValidation.newPassword.1"));
        }
        validateUnicode(value);
        int length = value.codePointCount(0, value.length());
        if (length < 12 || length > 128) {
            throw invalid(Messages.text("error.auth.identityValidation.newPassword.2"));
        }
        if (value.isBlank() || value.codePoints().distinct().limit(2).count() < 2
            || COMMON_PASSWORDS.contains(value.trim().toLowerCase(Locale.ROOT))) {
            throw invalid(Messages.text("error.auth.identityValidation.newPassword.3"));
        }
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid(Messages.text("error.auth.identityValidation.validateUnicode.1"));
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid(Messages.text("error.auth.identityValidation.validateUnicode.1"));
            }
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
