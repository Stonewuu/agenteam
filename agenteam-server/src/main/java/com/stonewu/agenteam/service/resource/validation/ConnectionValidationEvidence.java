package com.stonewu.agenteam.service.resource.validation;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 发布只消费服务端保存且对应当前草稿的检查结果，客户端不能声明检查成功。
 */
@Component
public class ConnectionValidationEvidence {
    private final CredentialMapper credentials;
    private final Clock clock;

    public ConnectionValidationEvidence(CredentialMapper credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    public void credential(AuthContext actor, String id, Set<String> kinds) {
        if (id == null) {
            return;
        }
        if (credentials.find(actor.enterpriseId(), id)
            .filter(value -> value.status().equals("active") && kinds.contains(value.kind())).isEmpty()) {
            throw ApiException.invalidField("config.credentialId", Messages.text("error.resource.connectionValidationEvidence.credential.1"));
        }
    }

    public void checked(ResourceRecord resource) {
        var evidence = resource.validation();
        if (evidence == null || !resource.configHash().equals(evidence.path("configHash").asText())
            || !evidence.path("connection").path("status").asText().equals("succeeded") || !recent(
            evidence.path("connection").path("checkedAt").asText())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CHECK_REQUIRED",
                Messages.text("error.resource.connectionValidationEvidence.checked.1", resource.kind().localizedLabel()));
        }
    }

    private boolean recent(String value) {
        try {
            Instant checked = Instant.parse(value), now = clock.instant();
            return !checked.isAfter(now) && checked.plus(Duration.ofMinutes(10)).isAfter(now);
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
