package com.stonewu.agenteam.service.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 按实际适配器的认证格式验证新值，错误不包含原始秘密。
 */
@Component
public class CredentialSecretValidation {
    private final ObjectMapper json;

    public CredentialSecretValidation(ObjectMapper json) {
        this.json = json;
    }

    public void validate(String kind, String secret) {
        if (secret == null || secret.isBlank() || secret.codePointCount(0, secret.length()) > 8192) {
            throw invalid();
        }
        switch (kind) {
            case "bearer", "api_key" -> {
                if (!secret.chars().allMatch(value -> value >= 33 && value <= 126)) {
                    throw invalid();
                }
            }
            case "basic" -> {
                if (secret.indexOf(':') < 1 || secret.chars().anyMatch(value -> value < 32 || value == 127)) {
                    throw invalid();
                }
            }
            case "database" -> {
                try {
                    var value = json.readTree(secret);
                    if (!value.isObject() || value.size() != 2 || !value.path("username").isTextual() || value.path(
                        "username").asText().isBlank()
                        || value.path("username").asText().length() > 128 || !value.path("password").isTextual()) {
                        throw invalid();
                    }
                    value.fieldNames().forEachRemaining(name -> {
                        if (!Set.of("username", "password").contains(name)) {
                            throw invalid();
                        }
                    });
                } catch (JsonProcessingException malformed) {
                    throw invalid();
                }
            }
            default -> throw ApiException.invalidField("kind", "请选择支持的凭据类型。");
        }
    }

    private static ApiException invalid() {
        return ApiException.invalidField("secret", "请填写当前认证类型要求的完整凭据，不能使用空值或控制字符。");
    }
}
