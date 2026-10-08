package com.stonewu.agenteam.mapper.usage;


import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriodState;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 次数规则、自然月计数和每次执行条目使用同一数据库事务。
 */
@Repository
public class QuotaMapper {
    public record Policy(String id, String type, String subjectId, Long limit) {
    }

    public record Bucket(String id, long used, long reserved) {
    }

    public record Entry(String bucketId, String state) {
    }

    private final QuotaSqlMapper statements;

    private final EnterpriseTableMapper enterpriseTableMapper;

    private final QuotaEntryTableMapper quotaEntryTableMapper;

    public QuotaMapper(QuotaSqlMapper statements, EnterpriseTableMapper enterpriseTableMapper,
                       QuotaEntryTableMapper quotaEntryTableMapper) {
        this.quotaEntryTableMapper = quotaEntryTableMapper;
        this.enterpriseTableMapper = enterpriseTableMapper;
        this.statements = statements;
    }

    public QuotaPeriodState lockEnterprise(String enterprise) {
        return DataAccessUtils.nullableSingleResult(statements.lockEnterpriseEnterprise(enterprise).stream().map(
            row -> new QuotaPeriodState(
                new QuotaPeriod(instant(row.getQuotaPeriodStart()), instant(row.getQuotaPeriodEnd()),
                    row.getQuotaTimezone()), row.getPendingQuotaTimezone())).toList());
    }

    public void period(String enterprise, QuotaPeriodState state) {
        enterpriseTableMapper.periodEnterprise(state.current().timezone(), state.pendingTimezone(),
            timestamp(state.current().start()), timestamp(state.current().end()), enterprise);
    }

    public Bucket bucket(String enterprise, Policy policy, QuotaPeriod period, Instant now) {
        statements.bucketQuotaBucket(UUID.randomUUID().toString(), enterprise, policy.id(), timestamp(period.start()),
            timestamp(period.end()), period.timezone(), timestamp(now));
        return DataAccessUtils.nullableSingleResult(
            statements.bucketQuotaBucket2(enterprise, policy.id(), timestamp(period.start())).stream()
                .map(row -> new Bucket(row.getId(), row.getUsedCount(), row.getReservedCount())).toList());
    }

    public List<Entry> entries(String enterprise, String run) {
        return statements.entriesQuotaEntry(enterprise, run).stream()
            .map(row -> new Entry(row.getBucketId(), row.getState())).toList();
    }

    public void reserve(String enterprise, String run, String bucket, Instant now) {
        statements.reserveQuotaEntry(enterprise, run, bucket, timestamp(now));
        statements.reserveQuotaBucket(timestamp(now), enterprise, bucket);
    }

    public void settle(String enterprise, String run, String bucket, boolean consumed, Instant now) {
        int changed = quotaEntryTableMapper.settleQuotaEntry(consumed ? "consumed" : "released", timestamp(now),
            enterprise, run, bucket);
        if (changed == 1) {
            statements.settleQuotaBucket(consumed ? 1 : 0, timestamp(now), enterprise, bucket);
        }
    }
}
