package com.stonewu.agenteam.service.security;

import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.model.security.entity.HttpConnectionCredentials;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 每次连接重新读取当前凭据，普通配置与工具参数中不出现认证头。
 */
@Service
public class HttpCredentialService {
    private final CredentialMapper credentials;
    private final PayloadEncryption encryption;

    public HttpCredentialService(CredentialMapper credentials, PayloadEncryption encryption) {
        this.credentials = credentials;
        this.encryption = encryption;
    }

    public HttpConnectionCredentials resolve(String enterprise, String id) {
        if (id == null) {
            return new HttpConnectionCredentials(Map.of(), List.of());
        }
        var record = credentials.find(enterprise, id).filter(value -> value.status().equals("active"))
            .orElseThrow(HttpCredentialService::invalid);
        String secret = encryption.decrypt(record.payload(), "credential:" + record.enterpriseId() + ":" + record.id(),
            String.class);
        if (secret.isBlank() || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
            throw invalid();
        }
        Map<String, String> headers = switch (record.kind()) {
            case "bearer" -> Map.of("Authorization", "Bearer " + secret);
            case "basic" -> {
                if (!secret.contains(":")) {
                    throw invalid();
                }
                yield Map.of("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            }
            case "api_key" -> Map.of("X-API-Key", secret);
            default -> throw invalid();
        };
        if (headers.values().stream()
            .anyMatch(value -> value.chars().anyMatch(character -> character < 32 || character > 126))) {
            throw invalid();
        }
        var protectedValues = new ArrayList<>(headers.values());
        protectedValues.add(secret);
        if (record.kind().equals("basic")) {
            protectedValues.add(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        }
        if (record.kind().equals("basic")) {
            protectedValues.add(secret.substring(secret.indexOf(':') + 1));
        }
        return new HttpConnectionCredentials(headers,
            protectedValues.stream().filter(value -> !value.isEmpty()).distinct().toList());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_UNAVAILABLE",
            "连接凭据已失效或格式不正确，请重新设置。");
    }
}
