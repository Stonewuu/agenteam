package com.stonewu.agenteam.service.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 确认凭证只包含关联摘要，并绑定当前企业、操作人和目标成员，十分钟后失效。
 */
@Service
public class MemberRemovalTokenService {
    public record Token(String fingerprint, long memberRevision, long expiresAt) {
    }

    public record Issued(String value, Instant expiresAt) {
    }

    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final ResourceJson values;
    private final Clock clock;

    public MemberRemovalTokenService(PayloadEncryption encryption, ObjectMapper json, ResourceJson values,
                                     Clock clock) {
        this.encryption = encryption;
        this.json = json;
        this.values = values;
        this.clock = clock;
    }

    public Issued issue(AuthContext actor, String member, MemberRemovalState state) {
        var expires = Instant.ofEpochMilli(clock.millis()).plusSeconds(600);
        try {
            var encrypted = encryption.encrypt(
                new Token(fingerprint(state), state.memberRevision(), expires.toEpochMilli()), binding(actor, member));
            return new Issued(Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(encrypted)),
                expires);
        } catch (Exception failed) {
            throw new IllegalStateException("成员移除影响暂时无法确认", failed);
        }
    }

    public void verify(AuthContext actor, String member, MemberRemovalState state, long revision, String value) {
        if (value == null || value.isBlank() || value.length() > 8192) {
            throw changed();
        }
        try {
            var encrypted = json.readValue(Base64.getUrlDecoder().decode(value), EncryptedPayload.class);
            var token = encryption.decrypt(encrypted, binding(actor, member), Token.class);
            if (token.expiresAt() <= clock.millis() || token.memberRevision() != revision || state.memberRevision() != revision || !fingerprint(
                state).equals(token.fingerprint())) {
                throw changed();
            }
        } catch (Exception failed) {
            throw changed();
        }
    }

    private String fingerprint(MemberRemovalState state) {
        return values.hash(json.valueToTree(state));
    }

    private String binding(AuthContext actor, String member) {
        return "member-removal:" + actor.enterpriseId() + ":" + actor.userId() + ":" + member;
    }

    private ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_CHANGED",
            "成员或关联内容已变化，或影响预览已过期，请重新查看移除影响。");
    }
}
