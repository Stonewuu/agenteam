package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 短时下载签名绑定当前成员和不可变内容；签名不能代替当前文件访问授权。
 */
@Service
public class FileDownloadTokenService {
    private final ApplicationSecretKeys keys;
    private final ObjectMapper json;
    private final Clock clock;

    public FileDownloadTokenService(ApplicationSecretKeys keys, ObjectMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    public record Token(String enterpriseId, String userId, String fileId, String sha256, long expiresAt) {
    }

    public record Issued(String value, Instant expiresAt) {
    }

    public Issued issue(AuthContext actor, FileRecord file) {
        Instant expires = clock.instant().plus(Duration.ofMinutes(10));
        if (file.expiresAt() != null && file.expiresAt().isBefore(expires)) {
            expires = file.expiresAt();
        }
        if (!expires.isAfter(clock.instant())) {
            throw invalid();
        }
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(
                new Token(actor.enterpriseId(), actor.userId(), file.id(), file.sha256(), expires.toEpochMilli())));
            return new Issued(
                payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(payload)), expires);
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成文件下载地址", failure);
        }
    }

    public void verify(String value, AuthContext actor, FileRecord file) {
        if (value == null || value.length() > 4096) {
            throw invalid();
        }
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]),
                Base64.getUrlDecoder().decode(parts[1]))) {
                throw invalid();
            }
            var token = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Token.class);
            if (!actor.enterpriseId().equals(token.enterpriseId()) || !actor.userId()
                .equals(token.userId()) || !file.id().equals(token.fileId())
                || !file.sha256().equals(token.sha256()) || token.expiresAt() <= clock.millis()) {
                throw invalid();
            }
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private byte[] signature(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(keys.requestKey());
        return mac.doFinal(("file-download:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.GONE, "DOWNLOAD_EXPIRED", "下载地址已失效，请重新获取。");
    }
}
