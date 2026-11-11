package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.permission.entity.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 管理表单使用的首版输入边界，长度按 Unicode 字符计算。
 */
public final class EnterpriseValidation {
    private EnterpriseValidation() {
    }

    public static String name(String value, String label, int maximum) {
        String result = text(value, label, maximum, false);
        if (result.isBlank()) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.name.1", label));
        }
        return result;
    }

    public static String description(String value) {
        return text(value, Messages.text("label.description"), 500, true);
    }

    public static String invitationNote(String value) {
        return text(value, Messages.text("label.invitation.note"), 200, true);
    }

    private static String text(String value, String label, int maximum, boolean multiline) {
        String result = value == null ? "" : value.trim();
        if (result.codePointCount(0, result.length()) > maximum) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.text.1", label, maximum));
        }
        for (int index = 0; index < result.length(); index++) {
            char character = result.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= result.length() || !Character.isLowSurrogate(result.charAt(index))) {
                    throw invalid(Messages.text("error.enterprise.enterpriseValidation.text.2", label));
                }
            } else if (Character.isLowSurrogate(character) || (Character.isISOControl(
                character) && !(multiline && "\r\n\t".indexOf(character) >= 0))) {
                throw invalid(Messages.text("error.enterprise.enterpriseValidation.text.2", label));
            }
        }
        return result;
    }

    public static String roleCode(String value) {
        String code = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z][a-z0-9_-]{2,63}")) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.roleCode.1"));
        }
        return code;
    }

    public static String status(String value, String fallback) {
        String status = value == null ? fallback : value;
        if (!Set.of("active", "disabled").contains(status)) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.status.1"));
        }
        return status;
    }

    public static DataScope scope(String value) {
        try {
            return DataScope.fromCode(value == null ? "enterprise" : value);
        } catch (IllegalArgumentException exception) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.scope.1"));
        }
    }

    public static long revision(Long value) {
        if (value == null || value < 1) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.revision.1"));
        }
        return value;
    }

    public static Set<String> identifiers(List<String> values, int minimum, int maximum, String label) {
        if (values == null || values.size() < minimum || values.size() > maximum) {
            throw invalid(Messages.text("error.enterprise.enterpriseValidation.identifiers.1", label, minimum, maximum));
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !value.equals(value.trim()) || value.codePointCount(0,
                value.length()) > 100
                || value.codePoints().anyMatch(Character::isISOControl) || !result.add(value)) {
                throw invalid(Messages.text("error.enterprise.enterpriseValidation.identifiers.2", label));
            }
        }
        return Set.copyOf(result);
    }

    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
