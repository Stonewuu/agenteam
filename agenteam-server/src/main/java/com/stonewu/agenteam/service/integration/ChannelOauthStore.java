package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets.Material;
import jakarta.servlet.http.HttpSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * 授权状态转换只占用短事务；先独占本次回调，再允许事务外交换平台授权码。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChannelOauthStore {
    private final ChannelOauthSessionMapper flows;
    private final ChannelAuthorizationSecrets secrets;
    private final ChannelAuthorizationPolicy policy;
    private final Clock clock;

    public ChannelOauthStore(ChannelOauthSessionMapper flows, ChannelAuthorizationSecrets secrets,
                              ChannelAuthorizationPolicy policy, Clock clock) {
        this.flows = flows;
        this.secrets = secrets;
        this.policy = policy;
        this.clock = clock;
    }

    public ChannelOauthSessionRow create(EnterpriseIntegrationRow connection, String purpose, UserEntity actor,
                                         String state, String nonce, String verifier, HttpSession session) {
        var row = new ChannelOauthSessionRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(connection.getEnterpriseId());
        row.setConnectionId(connection.getId());
        row.setConnectionRevision(connection.getRevision());
        row.setPurpose(purpose);
        if (actor != null) {
            row.setInitiatorUserId(actor.id());
            row.setInitiatorSessionVersion(actor.sessionVersion());
        }
        row.setStateHash(secrets.hash(state));
        row.setBrowserNonceHash(secrets.hash(nonce));
        row.setTransientEncryptedJson(secrets.encrypt(row.getId(), new Material(verifier, null)));
        row.setReturnPath("/enterprises/" + row.getEnterpriseId() + "/settings/channels");
        row.setStatus("pending");
        row.setExpiresAt(clock.instant().plus(Duration.ofMinutes(5)));
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        policy.current(row, session, true);
        flows.insert(row);
        return row;
    }

    public ChannelOauthSessionRow claim(String connection, String state, String nonce, HttpSession session) {
        if (state == null || state.length() > 256 || nonce == null) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        var row = flows.selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getStateHash, secrets.hash(state))
            .eq(ChannelOauthSessionRow::getConnectionId, connection));
        valid(row, "pending", nonce);
        policy.current(row, session, false);
        if (flows.update(update(row, "pending").set(ChannelOauthSessionRow::getStatus, "processing")) != 1) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        row.setStatus("processing");
        return row;
    }

    public void awaitConfirmation(String id, String nonce, String confirmation, ChannelIdentity identity, HttpSession session) {
        var row = require(id, "processing", nonce, session);
        var deadline = clock.instant().plusSeconds(60);
        if (deadline.isAfter(row.getExpiresAt())) {
            deadline = row.getExpiresAt();
        }
        if (flows.update(update(row, "processing").set(ChannelOauthSessionRow::getStatus, "awaiting_confirmation")
            .set(ChannelOauthSessionRow::getConfirmationTokenHash, secrets.hash(confirmation))
            .set(ChannelOauthSessionRow::getConfirmationExpiresAt, deadline)
            .set(ChannelOauthSessionRow::getTransientEncryptedJson, secrets.encrypt(id, new Material(null, identity)))) != 1) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
    }

    public ChannelOauthSessionRow require(String id, String status, String nonce, HttpSession session) {
        var row = id == null ? null : flows.selectById(id);
        valid(row, status, nonce);
        policy.current(row, session, true);
        return row;
    }

    public ChannelOauthSessionRow confirmation(String id, String nonce, String confirmation, HttpSession session) {
        var row = require(id, "awaiting_confirmation", nonce, session);
        if (row.getConfirmationExpiresAt() == null || !row.getConfirmationExpiresAt().isAfter(clock.instant())
            || !secrets.matches(confirmation, row.getConfirmationTokenHash())) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        return row;
    }

    public void requireEnterprise(String id, String enterprise) {
        var row = id == null ? null : flows.selectById(id);
        if (row == null || !enterprise.equals(row.getEnterpriseId())) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
    }

    public void consume(ChannelOauthSessionRow row, String result) {
        var changes = update(row, row.getStatus());
        if ("awaiting_confirmation".equals(row.getStatus())) {
            changes.gt(ChannelOauthSessionRow::getConfirmationExpiresAt, clock.instant());
        }
        if (flows.update(changes.set(ChannelOauthSessionRow::getStatus, "consumed")
            .set(ChannelOauthSessionRow::getConsumedAt, clock.instant()).set(ChannelOauthSessionRow::getResultCode, result)
            .set(ChannelOauthSessionRow::getTransientEncryptedJson, null)
            .set(ChannelOauthSessionRow::getConfirmationTokenHash, null)) != 1) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
    }

    public void fail(String id) {
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getId, id)
            .in(ChannelOauthSessionRow::getStatus, "processing", "awaiting_confirmation")
            .set(ChannelOauthSessionRow::getStatus, "failed").set(ChannelOauthSessionRow::getResultCode, "AUTHORIZATION_FAILED")
            .set(ChannelOauthSessionRow::getTransientEncryptedJson, null).set(ChannelOauthSessionRow::getConfirmationTokenHash, null)
            .set(ChannelOauthSessionRow::getUpdatedAt, clock.instant()));
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .in(ChannelOauthSessionRow::getStatus, "pending", "processing", "awaiting_confirmation")
            .and(group -> group.le(ChannelOauthSessionRow::getExpiresAt, clock.instant()).or(confirmation -> confirmation
                .eq(ChannelOauthSessionRow::getStatus, "awaiting_confirmation")
                .le(ChannelOauthSessionRow::getConfirmationExpiresAt, clock.instant())))
            .set(ChannelOauthSessionRow::getStatus, "expired").set(ChannelOauthSessionRow::getTransientEncryptedJson, null)
            .set(ChannelOauthSessionRow::getConfirmationTokenHash, null).set(ChannelOauthSessionRow::getUpdatedAt, clock.instant()));
    }

    private void valid(ChannelOauthSessionRow row, String status, String nonce) {
        if (row == null || !status.equals(row.getStatus()) || !row.getExpiresAt().isAfter(clock.instant())
            || !secrets.matches(nonce, row.getBrowserNonceHash())) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
    }

    private LambdaUpdateWrapper<ChannelOauthSessionRow> update(ChannelOauthSessionRow row, String status) {
        return new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getId, row.getId())
            .eq(ChannelOauthSessionRow::getEnterpriseId, row.getEnterpriseId()).eq(ChannelOauthSessionRow::getStatus, status)
            .gt(ChannelOauthSessionRow::getExpiresAt, clock.instant()).set(ChannelOauthSessionRow::getUpdatedAt, clock.instant());
    }
}
