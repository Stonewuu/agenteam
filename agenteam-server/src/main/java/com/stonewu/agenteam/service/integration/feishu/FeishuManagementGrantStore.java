package com.stonewu.agenteam.service.integration.feishu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.integration.FeishuManagementGrantMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementGrantRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 用户授权独立保存，刷新先领取短期处理资格，禁止并发使用同一个刷新凭据。 */
@Service
public class FeishuManagementGrantStore {
    private final FeishuManagementGrantMapper grants;
    private final FeishuSetupCipher cipher;
    private final Clock clock;

    public FeishuManagementGrantStore(FeishuManagementGrantMapper grants, FeishuSetupCipher cipher, Clock clock) {
        this.grants = grants;
        this.cipher = cipher;
        this.clock = clock;
    }

    public FeishuManagementGrantRow find(String enterprise, String connection, String user) {
        return grants.selectOne(new LambdaQueryWrapper<FeishuManagementGrantRow>()
            .eq(FeishuManagementGrantRow::getEnterpriseId, enterprise).eq(FeishuManagementGrantRow::getConnectionId, connection)
            .eq(FeishuManagementGrantRow::getUserId, user));
    }

    public Map<String, FeishuManagementGrantRow> list(String enterprise, String user) {
        return grants.selectList(new LambdaQueryWrapper<FeishuManagementGrantRow>()
            .eq(FeishuManagementGrantRow::getEnterpriseId, enterprise).eq(FeishuManagementGrantRow::getUserId, user))
            .stream().collect(Collectors.toMap(FeishuManagementGrantRow::getConnectionId, value -> value));
    }

    public boolean usable(FeishuManagementGrantRow row, EnterpriseIntegrationRow connection) {
        return row != null && row.getStatus().equals("authorized") && row.getCredentialRevision().equals(connection.getCredentialRevision())
            && row.getEncryptedJson() != null && (row.getExpiresAt() != null && row.getExpiresAt().isAfter(clock.instant())
                || row.getRefreshExpiresAt() != null && row.getRefreshExpiresAt().isAfter(clock.instant()));
    }

    @Transactional
    public void authorize(String enterprise, EnterpriseIntegrationRow connection, String user, JsonNode result) {
        var row = find(enterprise, connection.getId(), user);
        if (row == null) {
            row = new FeishuManagementGrantRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(enterprise);
            row.setConnectionId(connection.getId());
            row.setUserId(user);
            row.setCredentialRevision(connection.getCredentialRevision());
            row.setRevision(1L);
            row.setCreatedAt(clock.instant());
            apply(row, result);
            grants.insert(row);
        } else {
            row.setCredentialRevision(connection.getCredentialRevision());
            apply(row, result);
            save(row);
        }
    }

    @Transactional
    public FeishuManagementGrantRow claimRefresh(FeishuManagementGrantRow row) {
        if (row.getRefreshExpiresAt() == null || !row.getRefreshExpiresAt().isAfter(clock.instant())) {
            throw authorizationRequired();
        }
        if (row.getRefreshLeaseUntil() != null && !row.getRefreshLeaseUntil().isAfter(clock.instant())) {
            // 上次刷新可能已经消费旧凭据，不能在结果未知时再次使用。
            throw authorizationRequired();
        }
        int changed = grants.update(new LambdaUpdateWrapper<FeishuManagementGrantRow>().eq(FeishuManagementGrantRow::getId, row.getId())
            .eq(FeishuManagementGrantRow::getRevision, row.getRevision()).eq(FeishuManagementGrantRow::getStatus, "authorized")
            .isNull(FeishuManagementGrantRow::getRefreshLeaseUntil)
            .set(FeishuManagementGrantRow::getRefreshLeaseUntil, clock.instant().plusSeconds(30))
            .set(FeishuManagementGrantRow::getRevision, row.getRevision() + 1));
        if (changed != 1) {
            throw new ChannelProviderException("FEISHU_AUTHORIZATION_REFRESHING", "正在更新飞书授权，请稍后继续。");
        }
        return grants.selectById(row.getId());
    }

    @Transactional
    public void refreshed(FeishuManagementGrantRow row, JsonNode result) {
        apply(row, result);
        save(row);
    }

    @Transactional
    public void invalidate(FeishuManagementGrantRow row, boolean revoked) {
        grants.update(new LambdaUpdateWrapper<FeishuManagementGrantRow>().eq(FeishuManagementGrantRow::getId, row.getId())
            .eq(FeishuManagementGrantRow::getRevision, row.getRevision())
            .set(FeishuManagementGrantRow::getStatus, revoked ? "revoked" : "reauthorize")
            .set(FeishuManagementGrantRow::getEncryptedJson, null).set(FeishuManagementGrantRow::getRefreshLeaseUntil, null)
            .set(FeishuManagementGrantRow::getUpdatedAt, clock.instant())
            .set(FeishuManagementGrantRow::getRevision, row.getRevision() + 1));
    }

    public FeishuSetupCipher.Tokens tokens(FeishuManagementGrantRow row) {
        return cipher.decrypt(binding(row), row.getEncryptedJson(), FeishuSetupCipher.Tokens.class);
    }

    private void apply(FeishuManagementGrantRow row, JsonNode result) {
        String scopes = result.path("scope").asText();
        if (!Arrays.asList(scopes.split(" ")).contains(FeishuAppClient.MANAGE_SCOPE)) {
            throw authorizationRequired();
        }
        String access = FeishuAppClient.required(result, "access_token");
        long expire = result.path("expires_in").asLong();
        if (expire <= 0) {
            throw authorizationRequired();
        }
        String refresh = result.path("refresh_token").asText(null);
        long refreshExpire = result.path("refresh_token_expires_in").asLong();
        row.setEncryptedJson(cipher.encrypt(binding(row), new FeishuSetupCipher.Tokens(access, refresh)));
        row.setScopes(scopes);
        row.setStatus("authorized");
        row.setExpiresAt(clock.instant().plusSeconds(expire));
        row.setRefreshExpiresAt(refresh != null && refreshExpire > 0 ? clock.instant().plusSeconds(refreshExpire) : null);
        row.setRefreshLeaseUntil(null);
        row.setUpdatedAt(clock.instant());
    }

    private void save(FeishuManagementGrantRow row) {
        int changed = grants.update(new LambdaUpdateWrapper<FeishuManagementGrantRow>().eq(FeishuManagementGrantRow::getId, row.getId())
            .eq(FeishuManagementGrantRow::getEnterpriseId, row.getEnterpriseId()).eq(FeishuManagementGrantRow::getUserId, row.getUserId())
            .eq(FeishuManagementGrantRow::getRevision, row.getRevision())
            .set(FeishuManagementGrantRow::getCredentialRevision, row.getCredentialRevision())
            .set(FeishuManagementGrantRow::getStatus, row.getStatus()).set(FeishuManagementGrantRow::getEncryptedJson, row.getEncryptedJson())
            .set(FeishuManagementGrantRow::getScopes, row.getScopes()).set(FeishuManagementGrantRow::getExpiresAt, row.getExpiresAt())
            .set(FeishuManagementGrantRow::getRefreshExpiresAt, row.getRefreshExpiresAt()).set(FeishuManagementGrantRow::getRefreshLeaseUntil, null)
            .set(FeishuManagementGrantRow::getUpdatedAt, clock.instant()).set(FeishuManagementGrantRow::getRevision, row.getRevision() + 1));
        if (changed != 1) {
            throw ApiException.versionConflict(row.getRevision());
        }
        row.setRevision(row.getRevision() + 1);
    }

    private String binding(FeishuManagementGrantRow row) {
        return "grant:" + row.getEnterpriseId() + ":" + row.getConnectionId() + ":" + row.getUserId() + ":" + row.getId();
    }

    public static ChannelProviderException authorizationRequired() {
        return new ChannelProviderException("FEISHU_AUTHORIZATION_REQUIRED", "请先授权飞书管理应用，再继续创建。");
    }
}
