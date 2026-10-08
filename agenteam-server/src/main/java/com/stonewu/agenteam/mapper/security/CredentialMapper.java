package com.stonewu.agenteam.mapper.security;

import com.stonewu.agenteam.model.security.entity.CredentialRecord;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * 密文、随机数与认证校验值分列保存，编码转换不暴露明文。
 */
@Repository
public class CredentialMapper {
    private final CredentialSqlMapper statements;

    public CredentialMapper(CredentialSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<CredentialRecord> find(String enterprise, String id) {
        return statements.findCredential(enterprise, id).stream().map(rows -> {

            byte[] encrypted = rows.getCiphertext();

            byte[] tag = rows.getAuthTag();

            byte[] combined = Arrays.copyOf(encrypted, encrypted.length + tag.length);

            System.arraycopy(tag, 0, combined, encrypted.length, tag.length);

            return new CredentialRecord(rows.getId(), rows.getEnterpriseId(), rows.getKind(), rows.getStatus(),
                new EncryptedPayload(rows.getKeyVersion(), Base64.getEncoder().encodeToString(rows.getNonce()),
                    Base64.getEncoder().encodeToString(combined)));

        }).findFirst();
    }

    public void store(String id, String enterprise, String name, String kind, EncryptedPayload payload, String actor,
                      boolean exists, Instant now) {
        byte[] combined = Base64.getDecoder().decode(payload.ciphertext());
        byte[] encrypted = Arrays.copyOf(combined, combined.length - 16);
        byte[] tag = Arrays.copyOfRange(combined, combined.length - 16, combined.length);
        byte[] nonce = Base64.getDecoder().decode(payload.nonce());
        if (exists) {
            statements.storeCredential(encrypted, nonce, tag, payload.keyVersion(), actor, Timestamp.from(now),
                enterprise, id);
        } else {
            statements.insertCredential(id, enterprise, name, kind, encrypted, nonce, tag, payload.keyVersion(), actor,
                Timestamp.from(now));
        }
    }
}
