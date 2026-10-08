package com.stonewu.agenteam.configuration.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 显式配置优先，未配置的密钥从本机文件生成并复用；请求摘要与内容加密使用独立密钥。
 */
@Component
public class ApplicationSecretKeys {

    private final SecretKey requestKey;

    private final Map<String, SecretKey> encryptionKeys;

    private final String activeVersion;

    public ApplicationSecretKeys(@Value("${agenteam.security.request-signing-key:}") String signingKey,
                                 @Value("${agenteam.security.encryption-keys:}") String configuredKeys,
                                 @Value("${agenteam.security.active-encryption-key:1}") String activeVersion,
                                 @Value("${agenteam.security.keys-file:.agenteam/security/application-keys.properties}") String keysFile) {
        if (!activeVersion.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("内容加密密钥版本格式不正确");
        }
        SecretKey signing = signingKey.isBlank() ? null : decode(signingKey, "HmacSHA256");
        Map<String, SecretKey> keys = decodeEncryptionKeys(configuredKeys, activeVersion);
        if (signing == null || keys.isEmpty()) {
            var generated = ApplicationSecretKeyFile.loadOrCreate(keysFile, activeVersion);
            if (signing == null) {
                signing = decode(generated.signingKey(), "HmacSHA256");
            }
            if (keys.isEmpty()) {
                keys = decodeEncryptionKeys(generated.encryptionKeys(), activeVersion);
            }
        }
        requestKey = signing;
        encryptionKeys = Map.copyOf(keys);
        this.activeVersion = activeVersion;
    }

    private Map<String, SecretKey> decodeEncryptionKeys(String configuredKeys, String activeVersion) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        if (!configuredKeys.isBlank()) {
            try {
                Map<String, String> encoded = new ObjectMapper().readValue(configuredKeys, new TypeReference<>() {
                });
                if (encoded.isEmpty() || encoded.size() > 20) {
                    throw new IllegalArgumentException();
                }
                for (var entry : encoded.entrySet()) {
                    if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) {
                        throw new IllegalArgumentException();
                    }
                    keys.put(entry.getKey(), decode(entry.getValue(), "AES"));
                }
                if (!keys.containsKey(activeVersion)) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception exception) {
                throw new IllegalArgumentException("内容加密密钥配置格式不正确");
            }
        }
        return keys;
    }

    public SecretKey requestKey() {
        return requestKey;
    }

    public String activeVersion() {
        encryptionKey(activeVersion);
        return activeVersion;
    }

    public SecretKey encryptionKey(String version) {
        SecretKey key = encryptionKeys.get(version);
        if (key == null) {
            throw unavailable(
                "未找到版本 " + version + " 的内容加密密钥，请检查 agenteam.security.encryption-keys 和 agenteam.security.active-encryption-key。");
        }
        return key;
    }

    private SecretKey decode(String value, String algorithm) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(bytes, algorithm);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("部署密钥必须是经过 Base64 编码的三十二字节随机值");
        }
    }

    private ApiException unavailable(String reason) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "暂时无法提交此操作，请联系管理员。", new IllegalStateException(reason));
    }
}
