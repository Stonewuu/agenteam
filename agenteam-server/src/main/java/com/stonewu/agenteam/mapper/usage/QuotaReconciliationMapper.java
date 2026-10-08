package com.stonewu.agenteam.mapper.usage;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 按真实执行条目核对已用和预留，已释放条目不计入二者。
 */
@Repository
public class QuotaReconciliationMapper {
    public record Difference(String bucketId, long savedUsed, long savedReserved, long actualUsed,
                             long actualReserved) {
    }

    private final QuotaReconciliationSqlMapper statements;

    public QuotaReconciliationMapper(QuotaReconciliationSqlMapper statements) {
        this.statements = statements;
    }

    public List<String> enterprises() {
        return statements.enterprisesQuotaBucket();
    }

    public List<Difference> differences(String enterprise) {
        return statements.differencesQuotaBucket(enterprise).stream().map(
            row -> new Difference(row.getId(), row.getUsedCount(), row.getReservedCount(), row.getActualUsed(),
                row.getActualReserved())).toList();
    }

    public void repair(String enterprise, Difference value, Instant now) {
        int changed = statements.repairQuotaBucket(value.actualUsed(), value.actualReserved(), timestamp(now),
            enterprise, value.bucketId());
        if (changed != 1) {
            throw new IllegalStateException("次数记录在核对期间发生变化，当前修正未提交");
        }
    }
}
