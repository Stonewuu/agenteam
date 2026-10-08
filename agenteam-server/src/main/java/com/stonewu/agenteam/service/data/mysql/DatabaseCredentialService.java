package com.stonewu.agenteam.service.data.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 每次连接读取当前凭据，凭据对象不在字符串描述中输出秘密。
 */
@Service
public class DatabaseCredentialService {
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[数据库认证信息已隐藏]";
        }
    }

    private final CredentialMapper credentials;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;

    public DatabaseCredentialService(CredentialMapper credentials, PayloadEncryption encryption, ObjectMapper json) {
        this.credentials = credentials;
        this.encryption = encryption;
        this.json = json;
    }

    public Credentials resolve(String enterprise, String id) {
        if (id == null) {
            throw invalid();
        }
        var row = credentials.find(enterprise, id)
            .filter(value -> value.kind().equals("database") && value.status().equals("active"))
            .orElseThrow(this::invalid);
        try {
            var value = json.readTree(
                encryption.decrypt(row.payload(), "credential:" + enterprise + ":" + id, String.class));
            if (value == null || !value.path("username").isTextual() || value.path("username").asText()
                .isBlank() || !value.path("password").isTextual()) {
                throw invalid();
            }
            return new Credentials(value.path("username").asText(), value.path("password").asText());
        } catch (Exception invalid) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_UNAVAILABLE",
            "数据库凭据已失效或格式不正确，请重新设置。");
    }
}
