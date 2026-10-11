package com.stonewu.agenteam.service.modelusage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceStore;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageCostItemMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageEvidenceMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.service.modelpricing.ModelPriceCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 恢复任务仅使用已保存的事实，永远不会通过再次调用厂商补用量。
 */
@Service
public class ModelUsageRecoveryService {
    private final ModelUsageRecordMapper rows;
    private final ModelUsageCostItemMapper items;
    private final ModelPriceStore prices;
    private final ModelUsageEvidenceMapper evidence;
    private final ModelPriceCalculator calculator;
    private final Clock clock;

    public ModelUsageRecoveryService(ModelUsageRecordMapper rows, ModelUsageCostItemMapper items, ModelPriceStore prices,
                                     ModelUsageEvidenceMapper evidence, ModelPriceCalculator calculator, Clock clock) {
        this.rows = rows;
        this.items = items;
        this.prices = prices;
        this.evidence = evidence;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recoverInterrupted() {
        var now = clock.instant();
        for (var row : rows.lockExpired(now)) {
            var status = row.getUsageStatus().equals("complete") ? "partial" : row.getUsageStatus();
            rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ModelUsageRecordRow::getId, row.getId()).eq(ModelUsageRecordRow::getRevision, row.getRevision())
                .set(ModelUsageRecordRow::getCallStatus, "interrupted").set(ModelUsageRecordRow::getUsageStatus, status)
                .set(ModelUsageRecordRow::getFinishedAt, now).set(ModelUsageRecordRow::getRequestLeaseUntil, null)
                .set(ModelUsageRecordRow::getErrorCategory, "PROCESS_INTERRUPTED").set(ModelUsageRecordRow::getNextCalculationAt, now)
                .set(ModelUsageRecordRow::getUpdatedAt, now).setIncrBy(ModelUsageRecordRow::getRevision, 1));
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Work> claimCalculations() {
        var now = clock.instant();
        var selected = rows.lockPendingCalculations(now);
        for (var row : selected) {
            rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, row.getEnterpriseId())
                .eq(ModelUsageRecordRow::getId, row.getId()).eq(ModelUsageRecordRow::getRevision, row.getRevision())
                .set(ModelUsageRecordRow::getCalculationLeaseUntil, now.plusSeconds(120))
                .setIncrBy(ModelUsageRecordRow::getCalculationAttempts, 1));
        }
        return selected.stream().map(row -> new Work(row.getEnterpriseId(), row.getId(), row.getSnapshotSeq())).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void calculate(Work work) {
        var row = rows.lock(work.enterprise(), work.id());
        if (row == null || !row.getPriceStatus().equals("pending") || row.getFinishedAt() == null || row.getSnapshotSeq() != work.sequence()) {
            return;
        }
        var book = row.getPriceVersionId() == null ? null
            : prices.load(prices.version(row.getEnterpriseId(), row.getModelProfileId(), row.getPriceVersionId()));
        var result = calculator.calculate(book, row.getDispatchedAt(), row.getRequestedServiceTier(), row.getActualServiceTier(),
            evidence.decode(row), row.getUsageStatus().equals("complete"));
        items.delete(Wrappers.<ModelUsageCostItemRow>lambdaQuery().eq(ModelUsageCostItemRow::getEnterpriseId, work.enterprise())
            .eq(ModelUsageCostItemRow::getUsageRecordId, work.id()));
        var computed = result.items().stream().map(value -> {
            var item = new ModelUsageCostItemRow();
            item.setId(UUID.randomUUID().toString());
            item.setEnterpriseId(work.enterprise());
            item.setUsageRecordId(work.id());
            item.setMeter(value.meter());
            item.setCacheTtlSeconds(value.cacheTtlSeconds());
            item.setQuantity(value.quantity());
            item.setPriceRateId(value.rateId());
            item.setPricePerMillionSnapshot(value.pricePerMillion());
            item.setAmount(value.amount());
            item.setCreatedAt(clock.instant());
            return item;
        }).toList();
        if (!computed.isEmpty()) {
            items.insert(computed);
        }
        int changed = rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, work.enterprise())
            .eq(ModelUsageRecordRow::getId, work.id()).eq(ModelUsageRecordRow::getSnapshotSeq, work.sequence())
            .eq(ModelUsageRecordRow::getRevision, row.getRevision()).set(ModelUsageRecordRow::getPriceStatus, result.status())
            .set(ModelUsageRecordRow::getReasonCode, result.reason()).set(ModelUsageRecordRow::getPriceRuleId, result.ruleId())
            .set(ModelUsageRecordRow::getCurrency, result.currency()).set(ModelUsageRecordRow::getTotalAmount, result.totalAmount())
            .set(ModelUsageRecordRow::getCalculationLeaseUntil, null).set(ModelUsageRecordRow::getNextCalculationAt, null)
            .set(ModelUsageRecordRow::getUpdatedAt, clock.instant()).setIncrBy(ModelUsageRecordRow::getRevision, 1));
        if (changed != 1) {
            throw new IllegalStateException("模型金额保存时记录已变更。");
        }
    }

    public void postpone(Work work) {
        rows.update(Wrappers.<ModelUsageRecordRow>lambdaUpdate().eq(ModelUsageRecordRow::getEnterpriseId, work.enterprise())
            .eq(ModelUsageRecordRow::getId, work.id()).eq(ModelUsageRecordRow::getSnapshotSeq, work.sequence())
            .eq(ModelUsageRecordRow::getPriceStatus, "pending").set(ModelUsageRecordRow::getCalculationLeaseUntil, null)
            .set(ModelUsageRecordRow::getNextCalculationAt, clock.instant().plusSeconds(60)));
    }

    public record Work(String enterprise, String id, long sequence) {
    }
}
