package com.stonewu.agenteam.service.modelusage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceStore;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageEvidenceMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRegistrationMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 网络发送前登记，后续短事务仅更新同一调用；旧执行尝试的真实消耗仍可以保存。
 */
@Service
public class ModelUsageService {
    private final ModelUsageRecordMapper rows;
    private final ModelPriceStore prices;
    private final ModelUsageRegistrationMapper registration;
    private final ModelUsageEvidenceMapper evidence;
    private final Clock clock;
    private final ModelUsageRegistrationGuard guard;

    public ModelUsageService(ModelUsageRecordMapper rows, ModelPriceStore prices, ModelUsageRegistrationMapper registration,
                              ModelUsageEvidenceMapper evidence, Clock clock, ModelUsageRegistrationGuard guard) {
        this.rows = rows;
        this.prices = prices;
        this.registration = registration;
        this.evidence = evidence;
        this.clock = clock;
        this.guard = guard;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void register(String callId, String owner, ModelCallContext context, ModelProfileRecord model, ModelProviderRecord provider) {
        guard.requireActive(context);
        var now = clock.instant();
        var price = prices.current(context.enterpriseId(), model.id(), now);
        if (rows.insert(registration.create(callId, owner, context, model, provider, price, now)) != 1) {
            throw new IllegalStateException("模型请求登记未完成。");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean save(ModelUsageSnapshot value) {
        var usage = value.usage().usage();
        var encoded = evidence.encode(usage);
        var metadata = value.usage().metadata();
        var update = Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, value.enterpriseId())
            .eq(ModelUsageRecordRow::getId, value.callId()).eq(ModelUsageRecordRow::getRequestOwnerId, value.ownerId())
            .lt(ModelUsageRecordRow::getSnapshotSeq, value.sequence())
            .set(ModelUsageRecordRow::getInputTokens, usage.inputTokens()).set(ModelUsageRecordRow::getUncachedInputTokens, usage.uncachedInputTokens())
            .set(ModelUsageRecordRow::getOutputTokens, usage.outputTokens()).set(ModelUsageRecordRow::getCacheReadTokens, usage.cacheReadTokens())
            .set(ModelUsageRecordRow::getCacheWriteTokens, usage.cacheWriteTokens()).set(ModelUsageRecordRow::getReasoningTokens, usage.reasoningTokens())
            .set(ModelUsageRecordRow::getToolUsePromptTokens, usage.toolUsePromptTokens()).set(ModelUsageRecordRow::getTotalTokens, usage.totalTokens())
            .set(ModelUsageRecordRow::getCacheWriteDetailsJson, encoded.cacheDetails()).set(ModelUsageRecordRow::getFieldStatesJson, encoded.fieldStates())
            .set(ModelUsageRecordRow::getUsageEvidenceJson, encoded.evidence()).set(ModelUsageRecordRow::getUsageStatus, value.usage().status())
            .set(ModelUsageRecordRow::getProviderResponseId, metadata.responseId()).set(ModelUsageRecordRow::getResponseModel, metadata.model())
            .set(ModelUsageRecordRow::getActualServiceTier, limited(metadata.serviceTier(), 32))
            .set(ModelUsageRecordRow::getHttpStatus, value.httpStatus()).set(ModelUsageRecordRow::getProviderRequestId, value.providerRequestId())
            .set(ModelUsageRecordRow::getProviderErrorCode, value.providerErrorCode()).set(ModelUsageRecordRow::getErrorCategory, value.errorCategory())
            .set(ModelUsageRecordRow::getCallStatus, value.callStatus()).set(ModelUsageRecordRow::getSnapshotSeq, value.sequence())
            .set(value.usage().observed(), ModelUsageRecordRow::getLastUsageAt, value.capturedAt())
            .set(ModelUsageRecordRow::getUpdatedAt, clock.instant()).setIncrBy(ModelUsageRecordRow::getRevision, 1)
            .set(ModelUsageRecordRow::getRequestLeaseUntil, value.finished() ? null : clock.instant().plusSeconds(120));
        if (value.finished()) {
            update.set(ModelUsageRecordRow::getFinishedAt, value.capturedAt()).set(ModelUsageRecordRow::getPriceStatus, "pending")
                .set(ModelUsageRecordRow::getTotalAmount, null).set(ModelUsageRecordRow::getPriceRuleId, null)
                .set(ModelUsageRecordRow::getReasonCode, null).set(ModelUsageRecordRow::getNextCalculationAt, clock.instant())
                .set(ModelUsageRecordRow::getCalculationLeaseUntil, null);
        } else {
            // 结束后不接受后台线程已取得但尚未写入的旧快照。
            update.eq(ModelUsageRecordRow::getCallStatus, "in_flight");
        }
        return rows.update(update) == 1;
    }

    public void heartbeat(String enterprise, String id, String owner) {
        rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, enterprise)
            .eq(ModelUsageRecordRow::getId, id).eq(ModelUsageRecordRow::getRequestOwnerId, owner)
            .eq(ModelUsageRecordRow::getCallStatus, "in_flight").set(ModelUsageRecordRow::getRequestLeaseUntil, clock.instant().plusSeconds(120)));
    }

    private String limited(String value, int maximum) {
        return value == null ? null : value.substring(0, Math.min(maximum, value.length()));
    }
}
