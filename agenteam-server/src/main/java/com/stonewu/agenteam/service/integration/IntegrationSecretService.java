package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationSecretMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationSecretRow;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道密钥与企业、接入和用途绑定加密，普通资源凭据目录无法读取。
 */
@Service
public class IntegrationSecretService {
    private final EnterpriseIntegrationSecretMapper secrets;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;

    public IntegrationSecretService(EnterpriseIntegrationSecretMapper secrets, PayloadEncryption encryption, ObjectMapper json) {
        this.secrets = secrets;
        this.encryption = encryption;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void store(String enterprise, String connection, String secret, String actor, long revision, Instant now) {
        store(enterprise, connection, "app_secret", secret, actor, revision, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void store(String enterprise, String connection, String name, String secret, String actor, long revision, Instant now) {
        purpose(name);
        if (secret == null || secret.isBlank() || secret.length() > 8192 || secret.chars().anyMatch(value -> value < 33 || value > 126)) {
            throw ApiException.invalidField("secret", "请输入完整应用密钥，不能包含空格或控制字符。");
        }
        var previous = secrets.selectOne(query(enterprise, connection, name));
        String encrypted;
        try {
            encrypted = json.writeValueAsString(encryption.encrypt(secret, binding(enterprise, connection, name)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("应用密钥无法加密保存", failure);
        }
        if (previous == null) {
            var row = new EnterpriseIntegrationSecretRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(enterprise);
            row.setConnectionId(connection);
            row.setSecretName(name);
            row.setEncryptedValue(encrypted);
            row.setRevision(revision);
            row.setUpdatedBy(actor);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            secrets.insert(row);
        } else {
            int changed = secrets.update(new LambdaUpdateWrapper<EnterpriseIntegrationSecretRow>()
                .eq(EnterpriseIntegrationSecretRow::getId, previous.getId()).eq(EnterpriseIntegrationSecretRow::getEnterpriseId, enterprise)
                .eq(EnterpriseIntegrationSecretRow::getRevision, previous.getRevision())
                .set(EnterpriseIntegrationSecretRow::getEncryptedValue, encrypted).set(EnterpriseIntegrationSecretRow::getRevision, revision)
                .set(EnterpriseIntegrationSecretRow::getUpdatedBy, actor).set(EnterpriseIntegrationSecretRow::getUpdatedAt, now));
            if (changed != 1) {
                throw ApiException.versionConflict(previous.getRevision());
            }
        }
    }

    public String load(String enterprise, String connection) {
        return load(enterprise, connection, "app_secret");
    }

    public String load(String enterprise, String connection, String name) {
        purpose(name);
        var row = secrets.selectOne(query(enterprise, connection, name));
        if (row == null) {
            throw new ChannelProviderException("CHANNEL_SECRET_REQUIRED", "请先配置应用密钥。");
        }
        try {
            return encryption.decrypt(json.readValue(row.getEncryptedValue(), EncryptedPayload.class),
                binding(enterprise, connection, name), String.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的应用密钥无法读取", failure);
        }
    }

    public boolean configured(String enterprise, String connection) {
        return configured(enterprise, connection, "app_secret");
    }

    public boolean configured(String enterprise, String connection, String name) {
        purpose(name);
        return secrets.exists(query(enterprise, connection, name));
    }

    public Set<String> configured(String enterprise, List<String> connections) {
        return configured(enterprise, connections, "app_secret");
    }

    public Set<String> configured(String enterprise, List<String> connections, String purpose) {
        purpose(purpose);
        if (connections.isEmpty()) {
            return Set.of();
        }
        return secrets.selectList(new LambdaQueryWrapper<EnterpriseIntegrationSecretRow>()
                .select(EnterpriseIntegrationSecretRow::getConnectionId)
                .eq(EnterpriseIntegrationSecretRow::getEnterpriseId, enterprise)
                .eq(EnterpriseIntegrationSecretRow::getSecretName, purpose)
                .in(EnterpriseIntegrationSecretRow::getConnectionId, connections))
            .stream().map(EnterpriseIntegrationSecretRow::getConnectionId).collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<EnterpriseIntegrationSecretRow> query(String enterprise, String connection, String name) {
        return new LambdaQueryWrapper<EnterpriseIntegrationSecretRow>().eq(EnterpriseIntegrationSecretRow::getEnterpriseId, enterprise)
            .eq(EnterpriseIntegrationSecretRow::getConnectionId, connection).eq(EnterpriseIntegrationSecretRow::getSecretName, name);
    }

    private String binding(String enterprise, String connection, String name) {
        return "integration-secret:" + enterprise + ":" + connection + ":" + name;
    }

    private void purpose(String name) {
        if (!Set.of("app_secret", "chat_verification_token", "chat_encrypt_key").contains(name)) {
            throw new IllegalArgumentException("接入密钥用途未登记");
        }
    }
}
