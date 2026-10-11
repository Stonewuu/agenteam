package com.stonewu.agenteam.mapper.modelusage;

import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 登记内容只来自可信运行归属和本次读取的模型配置，API 密钥不会进入数据库对象。
 */
@Component
public class ModelUsageRegistrationMapper {
    public ModelUsageRecordRow create(String id, String owner, ModelCallContext context, ModelProfileRecord model,
                                      ModelProviderRecord provider, ModelPriceVersionRow price, Instant now) {
        if (context == null || !context.enterpriseId().equals(model.enterpriseId())
            || !context.enterpriseId().equals(provider.enterpriseId()) || !model.providerId().equals(provider.id())) {
            throw new IllegalArgumentException("模型调用缺少有效的企业归属。");
        }
        var row = new ModelUsageRecordRow();
        row.setId(id);
        row.setEnterpriseId(context.enterpriseId());
        row.setUserId(context.userId());
        row.setConversationId(context.conversationId());
        row.setRunId(context.runId());
        row.setRunAttemptNo(context.attemptNo());
        row.setRunLeaseVersion(context.leaseVersion());
        row.setAgentId(context.agentId());
        row.setAgentSessionId(context.agentSessionId());
        row.setWorkflowNodeId(context.workflowNodeId());
        row.setSourceKind(context.sourceKind());
        row.setSourceId(context.sourceId());
        row.setPurpose(context.purpose());
        row.setModelProfileId(model.id());
        row.setProviderId(provider.id());
        row.setModelProfileRevision(model.revision());
        row.setProviderRevision(provider.revision());
        row.setModelNameSnapshot(model.modelName());
        row.setProviderNameSnapshot(provider.name());
        row.setProtocol(provider.protocol());
        row.setUsageFormat(model.capabilities().protocolOptions().usageFormat());
        row.setRequestedServiceTier(model.capabilities().protocolOptions().serviceTier());
        row.setCallStatus("in_flight");
        row.setUsageStatus("not_reported");
        row.setPriceStatus("pending");
        row.setRegisteredAt(now);
        row.setDispatchedAt(now);
        row.setCacheWriteDetailsJson("{}");
        row.setFieldStatesJson("{}");
        row.setUsageEvidenceJson("{\"parserVersion\":2,\"fields\":{},\"problems\":[],\"unsupportedMeters\":[]}");
        row.setPriceVersionId(price == null ? null : price.getId());
        row.setCurrency(price == null ? null : price.getCurrency());
        row.setPricingAlgorithmVersion(ModelUsageEvidenceMapper.CURRENT_VERSION);
        row.setSnapshotSeq(0L);
        row.setRevision(1L);
        row.setRequestOwnerId(owner);
        row.setRequestLeaseUntil(now.plusSeconds(120));
        row.setCalculationAttempts(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
