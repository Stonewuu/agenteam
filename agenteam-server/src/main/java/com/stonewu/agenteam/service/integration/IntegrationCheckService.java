package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.integration.entity.ChannelApplicationCheck;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 应用校验在数据库事务外进行，完成后只能保存到发起检查时的配置版本。
 */
@Service
public class IntegrationCheckService {
    public record PreparedCheck(String enterpriseId, String connectionId, long revision,
                                ChannelApplicationCheck result, String errorCode, String errorSummary) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationCheckService.class);
    private final IntegrationManagementPolicy policy;
    private final IntegrationQueryService queries;
    private final IntegrationSecretService secrets;
    private final IntegrationProviderRegistry providers;

    public IntegrationCheckService(IntegrationManagementPolicy policy, IntegrationQueryService queries,
                                   IntegrationSecretService secrets, IntegrationProviderRegistry providers) {
        this.policy = policy;
        this.queries = queries;
        this.secrets = secrets;
        this.providers = providers;
    }

    public PreparedCheck prepare(UserEntity actor, String enterprise, boolean system, String id, long revision) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("应用网络校验不能在数据库事务内执行");
        }
        policy.require(actor, enterprise, system, "integration.test", false);
        var row = queries.require(enterprise, id);
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        try {
            var application = queries.application(row);
            var provider = providers.require(row.getProviderCode());
            var token = provider.fetchAccessToken(application, secrets.load(enterprise, id));
            return new PreparedCheck(enterprise, id, revision, provider.checkApplication(application, token), null, null);
        } catch (ChannelProviderException failure) {
            LOG.warn("企业应用校验失败，企业={}，接入={}", enterprise, id, failure);
            return new PreparedCheck(enterprise, id, revision, null, failure.code(), failure.getMessage());
        }
    }
}
