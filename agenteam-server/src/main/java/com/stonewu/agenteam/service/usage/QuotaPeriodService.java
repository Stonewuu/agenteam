package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.mapper.usage.QuotaMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 企业创建时已有明确周期，不依赖是否曾创建用量记录来判断时区何时生效。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class QuotaPeriodService {
    private final QuotaMapper quotas;
    private final QuotaPeriodCalculator calculator;
    private final Clock clock;

    public QuotaPeriodService(QuotaMapper quotas, QuotaPeriodCalculator calculator, Clock clock) {
        this.quotas = quotas;
        this.calculator = calculator;
        this.clock = clock;
    }

    public QuotaPeriod current(String enterprise) {
        var before = quotas.lockEnterprise(enterprise);
        var after = calculator.current(before, clock.instant());
        if (!before.equals(after)) {
            quotas.period(enterprise, after);
        }
        return after.current();
    }
}
