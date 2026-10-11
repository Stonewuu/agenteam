package com.stonewu.agenteam.model.modelusage.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 金额与大整数都返回十进制字符串，浏览器不重新累计浮点金额。 */
public record ModelUsageSummaryView(Instant from, Instant to, String timezone, Totals totals, List<Daily> days) {
    public record Totals(Instant firstRequestAt, String calls, String incompleteUsageCalls, String unpricedCalls,
                         String pendingCalls, String invalidUsageCalls, Map<String, Tokens> tokens, List<Money> amounts) {
    }

    public record Tokens(String value, String knownCalls, String notApplicableCalls) {
    }

    public record Money(String currency, String completeAmount, String completeCalls, String partialAmount, String partialCalls) {
    }

    public record Daily(String date, Totals totals) {
    }
}
