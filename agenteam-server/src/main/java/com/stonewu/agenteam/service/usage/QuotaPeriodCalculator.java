package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.model.usage.entity.QuotaPeriod;
import com.stonewu.agenteam.model.usage.entity.QuotaPeriodState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 时区切换后的第一个月接着原周期结束，不能漏掉或重复计算两个时区之间的时间。
 */
@Component
public class QuotaPeriodCalculator {
    public QuotaPeriodState current(QuotaPeriodState state, Instant now) {
        var current = state.current();
        if (now.isBefore(current.start())) {
            throw new IllegalStateException("次数周期起点晚于当前服务时间，不能重新解释已保存的用量");
        }
        if (now.isBefore(current.end())) {
            return state;
        }
        if (state.pendingTimezone() == null) {
            return new QuotaPeriodState(QuotaPeriod.containing(now, current.timezone()), null);
        }
        var nextMonth = YearMonth.from(current.end().atZone(ZoneId.of(current.timezone())));
        var newZone = ZoneId.of(state.pendingTimezone());
        var transitionEnd = nextMonth.plusMonths(1).atDay(1).atStartOfDay(newZone).toInstant();
        var next = now.isBefore(transitionEnd) ? new QuotaPeriod(current.end(), transitionEnd, state.pendingTimezone())
            : QuotaPeriod.containing(now, state.pendingTimezone());
        return new QuotaPeriodState(next, null);
    }
}
