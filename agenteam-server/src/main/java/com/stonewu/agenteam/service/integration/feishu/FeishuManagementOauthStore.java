package com.stonewu.agenteam.service.integration.feishu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.integration.FeishuManagementOauthMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementOauthRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/** 用户授权的回调只允许原浏览器、原用户和当前凭据版本消费一次。 */
@Service
public class FeishuManagementOauthStore {
    private final FeishuManagementOauthMapper flows;
    private final ChannelAuthorizationSecrets random;
    private final FeishuSetupCipher cipher;
    private final Clock clock;

    public FeishuManagementOauthStore(FeishuManagementOauthMapper flows, ChannelAuthorizationSecrets random,
            FeishuSetupCipher cipher, Clock clock) {
        this.flows = flows;
        this.random = random;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional
    public void start(EnterpriseIntegrationRow connection, UserEntity actor, boolean system, String nonce, String state, String verifier) {
        flows.update(new LambdaUpdateWrapper<FeishuManagementOauthRow>().eq(FeishuManagementOauthRow::getEnterpriseId, connection.getEnterpriseId())
            .eq(FeishuManagementOauthRow::getConnectionId, connection.getId()).eq(FeishuManagementOauthRow::getUserId, actor.id())
            .eq(FeishuManagementOauthRow::getStatus, "pending").set(FeishuManagementOauthRow::getStatus, "expired")
            .set(FeishuManagementOauthRow::getEncryptedJson, null).set(FeishuManagementOauthRow::getUpdatedAt, clock.instant()));
        var row = new FeishuManagementOauthRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(connection.getEnterpriseId());
        row.setConnectionId(connection.getId());
        row.setUserId(actor.id());
        row.setUserSessionVersion(actor.sessionVersion());
        row.setCredentialRevision(connection.getCredentialRevision());
        row.setSystemAccess(system);
        row.setStateHash(random.hash(state));
        row.setNonceHash(random.hash(nonce));
        row.setStatus("pending");
        row.setExpiresAt(clock.instant().plusSeconds(300));
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setEncryptedJson(cipher.encrypt(binding(row), verifier));
        flows.insert(row);
    }

    @Transactional
    public FeishuManagementOauthRow claim(String connection, String state, String nonce, UserEntity actor) {
        if (state == null || state.length() > 256 || nonce == null) {
            throw unavailable();
        }
        var row = flows.selectOne(new LambdaQueryWrapper<FeishuManagementOauthRow>()
            .eq(FeishuManagementOauthRow::getConnectionId, connection).eq(FeishuManagementOauthRow::getStateHash, random.hash(state)));
        if (row == null || !row.getStatus().equals("pending") || !row.getUserId().equals(actor.id())
            || row.getUserSessionVersion() != actor.sessionVersion() || !random.matches(nonce, row.getNonceHash())
            || !row.getExpiresAt().isAfter(clock.instant())) {
            throw unavailable();
        }
        if (flows.update(new LambdaUpdateWrapper<FeishuManagementOauthRow>().eq(FeishuManagementOauthRow::getId, row.getId())
                .eq(FeishuManagementOauthRow::getStatus, "pending").gt(FeishuManagementOauthRow::getExpiresAt, clock.instant())
                .set(FeishuManagementOauthRow::getStatus, "processing").set(FeishuManagementOauthRow::getUpdatedAt, clock.instant())) != 1) {
            throw unavailable();
        }
        return row;
    }

    public String verifier(FeishuManagementOauthRow row) {
        return cipher.decrypt(binding(row), row.getEncryptedJson(), String.class);
    }

    @Transactional
    public void finish(String id, boolean success) {
        if (flows.update(new LambdaUpdateWrapper<FeishuManagementOauthRow>().eq(FeishuManagementOauthRow::getId, id)
            .eq(FeishuManagementOauthRow::getStatus, "processing").gt(FeishuManagementOauthRow::getExpiresAt, clock.instant())
            .set(FeishuManagementOauthRow::getStatus, success ? "completed" : "failed")
            .set(FeishuManagementOauthRow::getEncryptedJson, null).set(FeishuManagementOauthRow::getUpdatedAt, clock.instant())) != 1) {
            throw unavailable();
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        flows.update(new LambdaUpdateWrapper<FeishuManagementOauthRow>().in(FeishuManagementOauthRow::getStatus, "pending", "processing")
            .le(FeishuManagementOauthRow::getExpiresAt, clock.instant()).set(FeishuManagementOauthRow::getStatus, "expired")
            .set(FeishuManagementOauthRow::getEncryptedJson, null).set(FeishuManagementOauthRow::getUpdatedAt, clock.instant()));
    }

    private String binding(FeishuManagementOauthRow row) {
        return "oauth:" + row.getEnterpriseId() + ":" + row.getUserId() + ":" + row.getId();
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "FEISHU_AUTHORIZATION_INVALID", "授权请求已失效，请从原群策页面重新发起。");
    }
}
