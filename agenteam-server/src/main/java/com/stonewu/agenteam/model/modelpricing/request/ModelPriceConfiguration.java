package com.stonewu.agenteam.model.modelpricing.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 一版完整价目；跨午夜时段在保存前转换为两个日期条件独立的规则。
 */
public record ModelPriceConfiguration(String currency, String timezone, LocalDate calendarValidThrough,
                                      String sourceReference, Instant sourceCheckedAt, String note, List<Rule> rules) {
    public record Rule(String name, int priority, boolean defaultRule, long contextMinTokens, Long contextMaxTokens,
                       String serviceTier, List<Time> times, List<DateCondition> dates, List<Rate> rates) {
    }

    public record Time(int weekday, int startMinute, int endMinute) {
    }

    public record DateCondition(LocalDate localDate, String kind, String note) {
    }

    public record Rate(String meter, int cacheTtlSeconds, String pricePerMillion) {
        public Rate(String meter, int cacheTtlSeconds, BigDecimal pricePerMillion) {
            this(meter, cacheTtlSeconds, pricePerMillion == null ? null : pricePerMillion.toPlainString());
        }

        public BigDecimal decimalPrice() {
            return new BigDecimal(pricePerMillion);
        }
    }
}
