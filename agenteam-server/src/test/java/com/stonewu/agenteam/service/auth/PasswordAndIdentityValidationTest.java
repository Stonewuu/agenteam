package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.configuration.auth.AccountPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class PasswordAndIdentityValidationTest {

    @Test
    void newPasswordsUseSaltedArgonAndKeepWhitespaceSignificant() {
        var encoder = new AccountPasswordEncoder();
        String password = "这是可以使用的长密码包含空格  ";
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertTrue(first.startsWith("$argon2id$"));
        assertTrue(first.contains("m=19456,t=2,p=1"));
        assertNotEquals(first, second);
        assertTrue(encoder.matches(password, first));
        assertFalse(encoder.matches(password.trim(), first));
        assertFalse(encoder.upgradeEncoding(first));
    }

    @Test
    void rejectsUnknownAndDamagedPasswordFormats() {
        var encoder = new AccountPasswordEncoder();
        assertFalse(encoder.matches("password", "{noop}password"));
        assertFalse(encoder.matches("password", "$argon2id$v=19$m=broken"));
    }

    @Test
    void countsUnicodeCharactersAndRejectsKnownWeakPasswords() {
        assertDoesNotThrow(() -> IdentityValidation.newPassword("甲乙丙丁戊己庚辛壬癸子丑"));
        assertDoesNotThrow(() -> IdentityValidation.newPassword("a😀".repeat(6)));
        assertThrows(ResponseStatusException.class, () -> IdentityValidation.newPassword("😀".repeat(6)));
        assertThrows(ResponseStatusException.class, () -> IdentityValidation.newPassword("Password123456"));
        assertThrows(ResponseStatusException.class, () -> IdentityValidation.newPassword("甲".repeat(12)));
        assertThrows(ResponseStatusException.class, () -> IdentityValidation.newPassword("有效内容不足以允许损坏字符\uD800"));
        assertDoesNotThrow(() -> IdentityValidation.displayName("😀".repeat(50)));
        assertThrows(ResponseStatusException.class, () -> IdentityValidation.displayName("😀".repeat(51)));
    }

    @Test
    void usernameDisplayIsPreservedAndLookupDoesNotDependOnMachineLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("ISTANBUL", IdentityValidation.username(" ISTANBUL "));
            assertEquals("istanbul", IdentityValidation.loginIdentifier(" ISTANBUL "));
            assertThrows(ResponseStatusException.class, () -> IdentityValidation.username("无效用户名"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
