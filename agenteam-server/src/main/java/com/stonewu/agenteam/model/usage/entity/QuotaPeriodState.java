package com.stonewu.agenteam.model.usage.entity;

/**
 * 待生效时区只有在当前已保存周期结束后才能应用。
 */
public record QuotaPeriodState(QuotaPeriod current, String pendingTimezone) {
}
