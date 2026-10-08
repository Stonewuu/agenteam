package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 一次性授权仅保存随机值摘要和加密材料，不保存授权码及用户令牌。 */
@Component
public class ChannelAuthorizationSecrets {
    private final SecureRandom random = new SecureRandom();
    private final PayloadEncryption encryption;
    private final ObjectMapper json;

    public ChannelAuthorizationSecrets(PayloadEncryption encryption, ObjectMapper json) {
        this.encryption = encryption;
        this.json = json;
    }

    public String random() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    public String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
    }

    public boolean matches(String value, String hash) {
        return value != null && hash != null && MessageDigest.isEqual(hash(value).getBytes(StandardCharsets.US_ASCII),
            hash.getBytes(StandardCharsets.US_ASCII));
    }

    public String encrypt(String id, Material material) {
        try {
            return json.writeValueAsString(encryption.encrypt(material, "channel-authorization:" + id));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("授权材料无法保存", failure);
        }
    }

    public Material decrypt(String id, String encrypted) {
        try {
            return encryption.decrypt(json.readValue(encrypted, EncryptedPayload.class), "channel-authorization:" + id, Material.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("授权材料无法读取", failure);
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法生成授权校验摘要", failure);
        }
    }

    public record Material(String verifier, ChannelIdentity identity) {
        @Override
        public String toString() {
            return "Material[授权材料已隐藏]";
        }
    }
}
