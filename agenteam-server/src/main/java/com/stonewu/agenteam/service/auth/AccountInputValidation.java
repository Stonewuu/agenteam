package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.service.http.ApiException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.time.ZoneId;

/**
 * 邮箱只接受邮箱地址本身；企业时区使用完整的标准时区名称。
 */
public final class AccountInputValidation {
    private AccountInputValidation() {
    }

    public static String email(String value, String field) {
        String email = value == null ? "" : value.trim();
        if (email.length() < 3 || email.length() > 254 || email.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField(field, "请输入有效的邮箱地址。");
        }
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            if (address.getPersonal() != null || !email.equals(address.getAddress()) || !email.contains("@")) {
                throw ApiException.invalidField(field, "请输入邮箱地址，不要包含姓名或其他文字。");
            }
            return email;
        } catch (AddressException exception) {
            throw ApiException.invalidField(field, "请输入有效的邮箱地址。");
        }
    }

    public static String timezone(String value) {
        String zone = value == null ? "Asia/Shanghai" : value;
        if (zone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(zone)) {
            throw ApiException.invalidField("timezone", "请选择有效时区。");
        }
        return zone;
    }
}
