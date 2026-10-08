package com.stonewu.agenteam.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;

/**
 * 预览凭证绑定用户和文件内容，不能复制到另一个企业或替换文件后继续确认。
 */
@Service
public class SkillImportTokenService {
    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final Clock clock;

    public SkillImportTokenService(PayloadEncryption encryption, ObjectMapper json, Clock clock) {
        this.encryption = encryption;
        this.json = json;
        this.clock = clock;
    }

    public record Token(String fileId, String sha256, long expiresAt) {
    }

    public String issue(AuthContext actor, FileRecord file) {
        try {
            var encrypted = encryption.encrypt(new Token(file.id(), file.sha256(), file.expiresAt().toEpochMilli()),
                binding(actor));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(encrypted));
        } catch (Exception unavailable) {
            throw new IllegalStateException("无法保存技能导入预览", unavailable);
        }
    }

    public Token read(AuthContext actor, String value) {
        if (value == null || value.length() > 8192) {
            throw invalid();
        }
        try {
            var payload = json.readValue(Base64.getUrlDecoder().decode(value), EncryptedPayload.class);
            var token = encryption.decrypt(payload, binding(actor), Token.class);
            if (token.expiresAt() <= clock.millis() || token.fileId() == null || token.sha256() == null) {
                throw invalid();
            }
            return token;
        } catch (Exception unavailable) {
            throw invalid();
        }
    }

    private static String binding(AuthContext actor) {
        return "skill-import:" + actor.enterpriseId() + ":" + actor.userId();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.GONE, "SKILL_IMPORT_EXPIRED", "技能导入预览已失效，请重新选择文件。");
    }
}
