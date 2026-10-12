package com.stonewu.agenteam.model.modelusage.entity;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

/** 数据库聚合使用十进制整数，避免大量请求求和后溢出。 */
@Getter
@Setter
public class ModelUsageAggregateRow {
    private String currency;
    private String day;
    private Instant firstRequestAt;
    private BigDecimal calls;
    private BigDecimal incompleteUsageCalls;
    private BigDecimal unpricedCalls;
    private BigDecimal pendingCalls;
    private BigDecimal invalidUsageCalls;
    private BigDecimal completePriceCalls;
    private BigDecimal partialPriceCalls;
    private BigDecimal completeAmount;
    private BigDecimal partialAmount;
    private BigDecimal inputTokens;
    private BigDecimal inputKnownCalls;
    private BigDecimal inputNotApplicableCalls;
    private BigDecimal uncachedInputTokens;
    private BigDecimal uncachedInputKnownCalls;
    private BigDecimal uncachedInputNotApplicableCalls;
    private BigDecimal outputTokens;
    private BigDecimal outputKnownCalls;
    private BigDecimal outputNotApplicableCalls;
    private BigDecimal cacheReadTokens;
    private BigDecimal cacheReadKnownCalls;
    private BigDecimal cacheReadNotApplicableCalls;
    private BigDecimal cacheWriteTokens;
    private BigDecimal cacheWriteKnownCalls;
    private BigDecimal cacheWriteNotApplicableCalls;
    private BigDecimal reasoningTokens;
    private BigDecimal reasoningKnownCalls;
    private BigDecimal reasoningNotApplicableCalls;
    private BigDecimal toolUsePromptTokens;
    private BigDecimal toolUsePromptKnownCalls;
    private BigDecimal toolUsePromptNotApplicableCalls;
    private BigDecimal totalTokens;
    private BigDecimal totalKnownCalls;
    private BigDecimal totalNotApplicableCalls;
}
