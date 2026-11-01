package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomBotIdentityClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomMemberClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/** 凭据轮换后的首次消息重新核验已有企业关联，不要求成员重新绑定。 */
@Service
public class WecomEnterpriseVerifier {
    private final WecomEnterpriseConnections connections;
    private final WecomEnterpriseProofStore proofs;
    private final IntegrationSecretService secrets;
    private final IntegrationQueryService queries;
    private final IntegrationTokenService tokens;
    private final WecomBotIdentityClient identity;
    private final WecomMemberClient members;

    public WecomEnterpriseVerifier(WecomEnterpriseConnections connections, WecomEnterpriseProofStore proofs,
            IntegrationSecretService secrets, IntegrationQueryService queries, IntegrationTokenService tokens,
            WecomBotIdentityClient identity, WecomMemberClient members) {
        this.connections = connections;
        this.proofs = proofs;
        this.secrets = secrets;
        this.queries = queries;
        this.tokens = tokens;
        this.identity = identity;
        this.members = members;
    }

    public Optional<WecomEnterpriseConnections.Verified> ensure(String enterprise, String connection) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("企微企业核验不能占用数据库事务");
        }
        var verified = connections.verified(enterprise, connection);
        if (verified.isPresent()) {
            return verified;
        }
        var candidate = connections.candidate(enterprise, connection);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        var current = candidate.get();
        var bot = identity.query(connection, current.bot().getExternalAppId(), secrets.load(enterprise, connection));
        var app = queries.application(current.enterprise());
        var token = tokens.get(app);
        try {
            var owner = members.resolveEncrypted(app, token, bot.encryptedOwnerId());
            return Optional.of(proofs.refresh(current, owner.userId()));
        } catch (ChannelProviderException failure) {
            if ("CHANNEL_TOKEN_EXPIRED".equals(failure.code())) {
                tokens.invalidate(app, token);
            }
            throw failure;
        }
    }
}
