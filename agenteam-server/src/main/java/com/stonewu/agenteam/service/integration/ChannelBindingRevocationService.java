package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.ChannelDeliveryCancellation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 持有企业锁后撤销绑定，同时停止待发送通知和已删除接入的未完成授权。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelBindingRevocationService {
    private final UserChannelBindingMapper bindings;
    private final ChannelOauthSessionMapper authorizations;
    private final ChannelDeliveryCancellation cancellations;
    private final Clock clock;

    public ChannelBindingRevocationService(UserChannelBindingMapper bindings, ChannelOauthSessionMapper authorizations,
                                           ChannelDeliveryCancellation cancellations, Clock clock) {
        this.bindings = bindings;
        this.authorizations = authorizations;
        this.cancellations = cancellations;
        this.clock = clock;
    }

    public void revoke(UserChannelBindingRow binding) {
        var now = clock.instant();
        int changed = bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, binding.getEnterpriseId())
            .eq(UserChannelBindingRow::getConnectionId, binding.getConnectionId())
            .eq(UserChannelBindingRow::getId, binding.getId()).eq(UserChannelBindingRow::getUserId, binding.getUserId())
            .eq(UserChannelBindingRow::getRevision, binding.getRevision()).ne(UserChannelBindingRow::getStatus, "revoked")
            .set(UserChannelBindingRow::getStatus, "revoked").set(UserChannelBindingRow::getRevokedAt, now)
            .set(UserChannelBindingRow::getUpdatedAt, now).set(UserChannelBindingRow::getRevision, binding.getRevision() + 1)
            .set(UserChannelBindingRow::getReceiveEnabled, false).set(UserChannelBindingRow::getChatEnabled, false)
            .set(UserChannelBindingRow::getExternalLoginEnabled, false));
        if (changed != 1) {
            throw ApiException.versionConflict(binding.getRevision());
        }
        cancellations.cancel(binding.getEnterpriseId(), binding.getConnectionId(), binding.getId(), binding.getUserId(), null);
    }

    public int revokeConnection(EnterpriseIntegrationRow connection) {
        var current = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, connection.getEnterpriseId())
            .eq(UserChannelBindingRow::getConnectionId, connection.getId()).ne(UserChannelBindingRow::getStatus, "revoked"));
        current.forEach(this::revoke);
        authorizations.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getEnterpriseId, connection.getEnterpriseId())
            .eq(ChannelOauthSessionRow::getConnectionId, connection.getId())
            .in(ChannelOauthSessionRow::getStatus, "pending", "processing", "awaiting_confirmation")
            .set(ChannelOauthSessionRow::getStatus, "expired").set(ChannelOauthSessionRow::getResultCode, "INTEGRATION_DELETED")
            .set(ChannelOauthSessionRow::getTransientEncryptedJson, null).set(ChannelOauthSessionRow::getConfirmationTokenHash, null)
            .set(ChannelOauthSessionRow::getUpdatedAt, clock.instant()));
        return current.size();
    }
}
