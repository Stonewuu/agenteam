package com.stonewu.agenteam.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 邮件等短期敏感内容加密后保存，并绑定业务记录，不能复制到另一条记录使用。
 */
@Service
public class PayloadEncryption {
    private final ApplicationSecretKeys keys;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public PayloadEncryption(ApplicationSecretKeys keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    public EncryptedPayload encrypt(Object value, String binding) {
        String version = keys.activeVersion();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey(version), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(value));
            return new EncryptedPayload(version, Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(encrypted));
        } catch (Exception exception) {
            throw new IllegalStateException("敏感内容无法加密保存", exception);
        }
    }

    public <T> T decrypt(EncryptedPayload payload, String binding, Class<T> type) {
        try {
            byte[] nonce = Base64.getDecoder().decode(payload.nonce());
            if (nonce.length != 12) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.encryptionKey(payload.keyVersion()),
                new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(payload.ciphertext()));
            return json.readValue(plain, type);
        } catch (Exception exception) {
            throw new IllegalStateException("已保存的敏感内容无法读取", exception);
        }
    }
}
