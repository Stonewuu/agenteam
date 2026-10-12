package com.stonewu.agenteam.service.modelusage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageCostItemMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageEvidenceMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 使用已保存的数字证据修正旧协议判断，保留发送时的价格版本和真实执行结果。 */
@Service
public class ModelUsageProtocolRepairService {
    private final ModelUsageRecordMapper rows;
    private final ModelUsageCostItemMapper items;
    private final ModelUsageEvidenceMapper evidence;
    private final Clock clock;

    public ModelUsageProtocolRepairService(ModelUsageRecordMapper rows, ModelUsageCostItemMapper items,
                                           ModelUsageEvidenceMapper evidence, Clock clock) {
        this.rows = rows;
        this.items = items;
        this.evidence = evidence;
        this.clock = clock;
    }

    public List<Candidate> candidates() {
        var query = Wrappers.<ModelUsageRecordRow>lambdaQuery()
            .select(ModelUsageRecordRow::getId, ModelUsageRecordRow::getEnterpriseId)
            .lt(ModelUsageRecordRow::getPricingAlgorithmVersion, ModelUsageEvidenceMapper.CURRENT_VERSION)
            .isNotNull(ModelUsageRecordRow::getFinishedAt)
            .and(lease -> lease.isNull(ModelUsageRecordRow::getCalculationLeaseUntil)
                .or().le(ModelUsageRecordRow::getCalculationLeaseUntil, clock.instant()))
            .orderByAsc(ModelUsageRecordRow::getPricingAlgorithmVersion, ModelUsageRecordRow::getFinishedAt, ModelUsageRecordRow::getId);
        return rows.selectPage(new Page<>(1, 100, false), query).getRecords().stream()
            .map(row -> new Candidate(row.getEnterpriseId(), row.getId())).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void repair(Candidate candidate) {
        var row = rows.lock(candidate.enterprise(), candidate.id());
        var now = clock.instant();
        if (row == null || row.getFinishedAt() == null
            || row.getPricingAlgorithmVersion() >= ModelUsageEvidenceMapper.CURRENT_VERSION
            || row.getCalculationLeaseUntil() != null && row.getCalculationLeaseUntil().isAfter(now)) {
            return;
        }
        var previous = evidence.decode(row);
        var excluded = previous.fieldStates().entrySet().stream().filter(entry -> entry.getValue().equals("not_applicable"))
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        var usage = previous.valid()
            ? new ModelUsageNormalizer().normalize(row.getProtocol(), row.getUsageFormat(), evidence.restoreSource(previous), excluded) : previous;
        var encoded = evidence.encode(usage);
        int changed = rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate()
            .eq(ModelUsageRecordRow::getEnterpriseId, candidate.enterprise()).eq(ModelUsageRecordRow::getId, candidate.id())
            .eq(ModelUsageRecordRow::getRevision, row.getRevision())
            .set(ModelUsageRecordRow::getInputTokens, usage.inputTokens()).set(ModelUsageRecordRow::getUncachedInputTokens, usage.uncachedInputTokens())
            .set(ModelUsageRecordRow::getOutputTokens, usage.outputTokens()).set(ModelUsageRecordRow::getCacheReadTokens, usage.cacheReadTokens())
            .set(ModelUsageRecordRow::getCacheWriteTokens, usage.cacheWriteTokens()).set(ModelUsageRecordRow::getReasoningTokens, usage.reasoningTokens())
            .set(ModelUsageRecordRow::getToolUsePromptTokens, usage.toolUsePromptTokens()).set(ModelUsageRecordRow::getTotalTokens, usage.totalTokens())
            .set(ModelUsageRecordRow::getCacheWriteDetailsJson, encoded.cacheDetails()).set(ModelUsageRecordRow::getFieldStatesJson, encoded.fieldStates())
            .set(ModelUsageRecordRow::getUsageEvidenceJson, encoded.evidence()).set(ModelUsageRecordRow::getUsageStatus, status(row, previous, usage))
            .set(ModelUsageRecordRow::getPricingAlgorithmVersion, ModelUsageEvidenceMapper.CURRENT_VERSION)
            .set(ModelUsageRecordRow::getPriceStatus, "pending").set(ModelUsageRecordRow::getTotalAmount, null)
            .set(ModelUsageRecordRow::getPriceRuleId, null).set(ModelUsageRecordRow::getReasonCode, null)
            .set(ModelUsageRecordRow::getNextCalculationAt, now).set(ModelUsageRecordRow::getCalculationLeaseUntil, null)
            .set(ModelUsageRecordRow::getUpdatedAt, now).setIncrBy(ModelUsageRecordRow::getRevision, 1));
        if (changed != 1) {
            throw new IllegalStateException("修正模型用量时记录已变更。");
        }
        // 不在修正后的用量旁继续显示旧金额，下一步由现有计算任务使用原价格版本重新计算。
        items.delete(Wrappers.<ModelUsageCostItemRow>lambdaQuery().eq(ModelUsageCostItemRow::getEnterpriseId, candidate.enterprise())
            .eq(ModelUsageCostItemRow::getUsageRecordId, candidate.id()));
    }

    public void postpone(Candidate candidate) {
        rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate()
            .eq(ModelUsageRecordRow::getEnterpriseId, candidate.enterprise()).eq(ModelUsageRecordRow::getId, candidate.id())
            .lt(ModelUsageRecordRow::getPricingAlgorithmVersion, ModelUsageEvidenceMapper.CURRENT_VERSION)
            .set(ModelUsageRecordRow::getCalculationLeaseUntil, clock.instant().plusSeconds(60)));
    }

    private String status(ModelUsageRecordRow row, ModelTokenUsage previous, ModelTokenUsage usage) {
        if (!usage.valid() || row.getUsageStatus().equals("invalid")) {
            return "invalid";
        }
        if (row.getUsageStatus().equals("not_reported")) {
            return "not_reported";
        }
        if (usage.complete() && (row.getUsageStatus().equals("complete")
            || row.getCallStatus().equals("completed") && !previous.complete())) {
            return "complete";
        }
        // 取消、中断及原本计数齐全却没有正常结束的流，不能升级为完整用量。
        return "partial";
    }

    public record Candidate(String enterprise, String id) {
    }
}
