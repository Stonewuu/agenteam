package com.stonewu.agenteam.model.modelpricing.response;

import java.time.Instant;

/** 参考汇率响应，供管理员导入模型价格草稿时按币种换算使用。 */
public record ExchangeRateView(String baseCurrency, String targetCurrency, String rate,
                               String effectiveDate, Instant fetchedAt,
                               String sourceName, String sourceUrl, boolean stale) {
}
