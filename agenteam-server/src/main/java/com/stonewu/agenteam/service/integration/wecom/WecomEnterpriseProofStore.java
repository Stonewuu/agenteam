package com.stonewu.agenteam.service.integration.wecom;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.WecomBotEnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.integration.entity.WecomBotEnterpriseRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** 核验期间凭据变化时拒绝提交，不能让旧平台响应覆盖新的企业关联。 */
@Service
public class WecomEnterpriseProofStore {
    private final EnterpriseMapper enterpriseRows;
    private final WecomEnterpriseConnections connections;
    private final WecomBotEnterpriseMapper proofs;
    private final ResourceJson json;
    private final Clock clock;

    public WecomEnterpriseProofStore(EnterpriseMapper enterpriseRows, WecomEnterpriseConnections connections,
            WecomBotEnterpriseMapper proofs, ResourceJson json, Clock clock) {
        this.enterpriseRows = enterpriseRows;
        this.connections = connections;
        this.proofs = proofs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public WecomEnterpriseConnections.Verified refresh(WecomEnterpriseConnections.Verified before, String owner) {
        String enterprise = before.bot().getEnterpriseId();
        enterpriseRows.lockEnterprise(enterprise).orElseThrow();
        var current = connections.candidate(enterprise, before.bot().getId()).orElseThrow(WecomEnterpriseProofStore::changed);
        if (!Objects.equals(current.bot().getCredentialRevision(), before.bot().getCredentialRevision())
            || !Objects.equals(current.enterprise().getCredentialRevision(), before.enterprise().getCredentialRevision())
            || !Objects.equals(current.enterprise().getId(), before.enterprise().getId())) {
            throw changed();
        }
        int count = proofs.update(new LambdaUpdateWrapper<WecomBotEnterpriseRow>().eq(WecomBotEnterpriseRow::getId, before.bot().getId())
            .eq(WecomBotEnterpriseRow::getEnterpriseId, enterprise).eq(WecomBotEnterpriseRow::getEnterpriseConnectionId, before.enterprise().getId())
            .eq(WecomBotEnterpriseRow::getBotCredentialRevision, before.proof().getBotCredentialRevision())
            .eq(WecomBotEnterpriseRow::getEnterpriseCredentialRevision, before.proof().getEnterpriseCredentialRevision())
            .set(WecomBotEnterpriseRow::getBotCredentialRevision, before.bot().getCredentialRevision())
            .set(WecomBotEnterpriseRow::getEnterpriseCredentialRevision, before.enterprise().getCredentialRevision())
            .set(WecomBotEnterpriseRow::getOwnerSubjectHash, json.hash(json.tree(List.of(before.enterprise().getExternalTenantId(), owner))))
            .set(WecomBotEnterpriseRow::getVerifiedAt, clock.instant()));
        var verified = connections.verified(enterprise, before.bot().getId());
        if (count != 1 && verified.isEmpty()) {
            throw changed();
        }
        return verified.orElseThrow(WecomEnterpriseProofStore::changed);
    }

    private static ChannelProviderException changed() {
        return new ChannelProviderException("WECOM_ENTERPRISE_CHANGED", "企业接入已变化，请稍后重试。");
    }
}
