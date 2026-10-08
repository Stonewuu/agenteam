package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.mapper.usage.QuotaReconciliationMapper;
import com.stonewu.agenteam.mapper.usage.QuotaReconciliationMapper.Difference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 与提交、开始和释放共用企业锁，核对结果不会覆盖正在变化的预留。
 */
@Service
public class QuotaReconciliationService {
    private final QuotaPeriodService periods;
    private final QuotaReconciliationMapper reconciliation;
    private final Clock clock;

    public QuotaReconciliationService(QuotaPeriodService periods, QuotaReconciliationMapper reconciliation,
                                      Clock clock) {
        this.periods = periods;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    public List<String> enterprises() {
        return reconciliation.enterprises();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Difference> check(String enterprise) {
        periods.current(enterprise);
        var differences = reconciliation.differences(enterprise);
        for (var value : differences) {
            reconciliation.repair(enterprise, value, clock.instant());
        }
        return differences;
    }
}
