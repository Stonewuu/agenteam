package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Base64;

/**
 * 预览凭证绑定原文件及明确的集合版本，不能改用另一文件或覆盖之后的编辑。
 */
@Service
public class DataImportTokenService {
    public record Token(String resourceId, long resourceRevision, String fileId, String sha256, String collectionId,
                        Long collectionRevision, long expiresAt) {
    }

    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final Clock clock;

    public DataImportTokenService(PayloadEncryption encryption, ObjectMapper json, Clock clock) {
        this.encryption = encryption;
        this.json = json;
        this.clock = clock;
    }

    public Token token(ResourceRecord resource, FileRecord file, DataCollectionRecord collection) {
        long expires = Math.min(clock.millis() + 30 * 60 * 1000,
            file.expiresAt() == null ? Long.MAX_VALUE : file.expiresAt().toEpochMilli());
        return new Token(resource.id(), resource.revision(), file.id(), file.sha256(),
            collection == null ? null : collection.id(), collection == null ? null : collection.revision(), expires);
    }

    public String issue(AuthContext actor, Token token) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsBytes(encryption.encrypt(token, binding(actor))));
        } catch (Exception unavailable) {
            throw new IllegalStateException("数据导入预览无法保存", unavailable);
        }
    }

    public Token read(AuthContext actor, String encoded) {
        if (encoded == null || encoded.length() > 8192) {
            throw invalid();
        }
        try {
            var token = encryption.decrypt(
                json.readValue(Base64.getUrlDecoder().decode(encoded), EncryptedPayload.class), binding(actor),
                Token.class);
            if (token.expiresAt() <= clock.millis() || token.fileId() == null || token.resourceId() == null || token.sha256() == null
                || ((token.collectionId() == null) != (token.collectionRevision() == null))) {
                throw invalid();
            }
            return token;
        } catch (Exception invalid) {
            throw invalid();
        }
    }

    private String binding(AuthContext actor) {
        return "data-import:" + actor.enterpriseId() + ":" + actor.userId();
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.GONE, "DATA_IMPORT_EXPIRED", "数据导入预览已经失效，请重新选择文件并预览。");
    }
}
