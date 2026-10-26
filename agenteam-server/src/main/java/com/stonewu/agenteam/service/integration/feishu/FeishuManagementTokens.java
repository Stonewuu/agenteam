package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** 刷新调用不占数据库事务；失败后要求重新授权，不重放已可能被消费的刷新凭据。 */
@Service
public class FeishuManagementTokens {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuManagementTokens.class);
    private final FeishuManagementGrantStore grants;
    private final IntegrationSecretService secrets;
    private final FeishuAppClient client;
    private final Clock clock;

    public FeishuManagementTokens(FeishuManagementGrantStore grants, IntegrationSecretService secrets, FeishuAppClient client, Clock clock) {
        this.grants = grants;
        this.secrets = secrets;
        this.client = client;
        this.clock = clock;
    }

    public String get(EnterpriseIntegrationRow connection, String user) {
        var row = grants.find(connection.getEnterpriseId(), connection.getId(), user);
        if (!grants.usable(row, connection)) {
            throw FeishuManagementGrantStore.authorizationRequired();
        }
        if (row.getExpiresAt().isAfter(clock.instant().plusSeconds(30))) {
            return grants.tokens(row).accessToken();
        }
        var claimed = grants.claimRefresh(row);
        try {
            var result = client.refresh(row.getId(), connection.getExternalAppId(),
                secrets.load(connection.getEnterpriseId(), connection.getId()), grants.tokens(claimed).refreshToken());
            grants.refreshed(claimed, result);
            return grants.tokens(claimed).accessToken();
        } catch (RuntimeException failure) {
            LOG.warn("飞书管理授权刷新失败，授权记录={}，接入={}", row.getId(), connection.getId(), failure);
            grants.invalidate(claimed, false);
            throw new ChannelProviderException("FEISHU_AUTHORIZATION_REQUIRED", "飞书管理授权无法刷新，请重新授权后继续。", failure);
        }
    }
}
