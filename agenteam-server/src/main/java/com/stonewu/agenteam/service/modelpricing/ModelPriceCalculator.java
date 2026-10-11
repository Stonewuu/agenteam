package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceBook;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCalculation;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRateRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 使用请求发送时固定的版本选取一条完整规则，不混用其他规则或当前新价格。
 */
@Component
public class ModelPriceCalculator {
    public ModelPriceCalculation calculate(ModelPriceBook book, Instant dispatchedAt, String requestedTier,
                                            String actualTier, ModelTokenUsage usage, boolean complete) {
        if (!usage.valid()) {
            return unavailable(book, "invalid", "INVALID_USAGE");
        }
        if (book == null) {
            return unavailable(null, "unpriced", "PRICE_NOT_CONFIGURED");
        }
        var local = dispatchedAt.atZone(ZoneId.of(book.version().getTimezone()));
        if (book.version().getCalendarValidThrough() != null
            && local.toLocalDate().isAfter(book.version().getCalendarValidThrough())) {
            return unavailable(book, "unpriced", "PRICE_CALENDAR_EXPIRED");
        }
        String tier = serviceTier(requestedTier, actualTier);
        if (tier == null) {
            return unavailable(book, "unpriced", "SERVICE_TIER_NOT_REPORTED");
        }
        var possible = book.rules().stream().filter(rule -> rule.definition().getServiceTier().equals(tier))
            .filter(rule -> matchesTime(rule, local))
            .sorted(Comparator.comparingInt((ModelPriceBook.Rule value) -> value.definition().getPriority()).reversed())
            .toList();
        ModelPriceBook.Rule selected = null;
        for (var rule : possible) {
            var definition = rule.definition();
            if (usage.inputTokens() == null) {
                // 尚不能排除较高优先级的上下文规则时，不能偷偷采用基础价。
                if (definition.getContextMinTokens() != 0 || definition.getContextMaxTokens() != null) {
                    return unavailable(book, "unpriced", "CONTEXT_NOT_REPORTED");
                }
                selected = rule;
                break;
            }
            if (usage.inputTokens() >= definition.getContextMinTokens()
                && (definition.getContextMaxTokens() == null || usage.inputTokens() < definition.getContextMaxTokens())) {
                selected = rule;
                break;
            }
        }
        if (selected == null) {
            return unavailable(book, "unpriced", "PRICE_RULE_NOT_FOUND");
        }
        return amounts(book, selected, usage, complete);
    }

    public static String serviceTier(String requested, String actual) {
        String tier = actual == null || actual.isBlank() ? requested : actual;
        if (tier == null || tier.isBlank() || tier.equals("default") || tier.equals("standard_only")) {
            return "standard";
        }
        return tier.equals("auto") ? null : tier;
    }

    public static boolean matchesTime(ModelPriceBook.Rule rule, ZonedDateTime local) {
        var date = local.toLocalDate();
        boolean hasIncludes = rule.dates().stream().anyMatch(value -> value.getKind().equals("include"));
        if (hasIncludes && rule.dates().stream().noneMatch(value -> value.getKind().equals("include")
            && value.getLocalDate().equals(date))) {
            return false;
        }
        if (rule.dates().stream().anyMatch(value -> value.getKind().equals("exclude") && value.getLocalDate().equals(date))) {
            return false;
        }
        int minute = local.getHour() * 60 + local.getMinute();
        return rule.times().isEmpty() || rule.times().stream().anyMatch(value -> value.getWeekday() == local.getDayOfWeek().getValue()
            && value.getStartMinute() <= minute && value.getEndMinute() > minute);
    }

    private ModelPriceCalculation amounts(ModelPriceBook book, ModelPriceBook.Rule rule,
                                           ModelTokenUsage usage, boolean complete) {
        var quantities = new ArrayList<Quantity>();
        boolean audioInput = usage.unsupportedMeters().contains("audio_input");
        boolean audioOutput = usage.unsupportedMeters().contains("audio_output");
        if (!audioInput) {
            quantities.add(new Quantity("input_uncached", 0, usage.uncachedInputTokens()));
            quantities.add(new Quantity("cache_read", 0, usage.cacheReadTokens()));
            usage.cacheWriteDetails().forEach((ttl, count) -> quantities.add(new Quantity("cache_write", ttl, count)));
            long detailed = usage.cacheWriteDetails().values().stream().mapToLong(Long::longValue).sum();
            if (usage.cacheWriteTokens() != null) {
                quantities.add(new Quantity("cache_write", 0, usage.cacheWriteTokens() - detailed));
            } else if (usage.cacheWriteDetails().isEmpty()) {
                quantities.add(new Quantity("cache_write", 0, null));
            }
        }
        if (!audioOutput) {
            quantities.add(new Quantity("output", 0, usage.outputTokens()));
        }
        boolean all = complete && usage.complete() && usage.unsupportedMeters().isEmpty();
        boolean missingPrice = false;
        List<ModelPriceCalculation.Item> items = new ArrayList<>();
        for (var quantity : quantities) {
            if (quantity.count() == null) {
                if (!"not_applicable".equals(usage.fieldStates().get(quantity.meter()))) {
                    all = false;
                }
                continue;
            }
            if (quantity.count() == 0) {
                continue;
            }
            ModelPriceRateRow rate = findRate(rule, quantity.meter(), quantity.ttl());
            if (rate == null) {
                all = false;
                missingPrice = true;
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(quantity.count()).multiply(rate.getPricePerMillion()).movePointLeft(6);
            items.add(new ModelPriceCalculation.Item(quantity.meter(), quantity.ttl(), quantity.count(), rate.getId(),
                rate.getPricePerMillion(), amount));
        }
        BigDecimal total = all ? items.stream().map(ModelPriceCalculation.Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        String reason = all ? null : !usage.unsupportedMeters().isEmpty() ? "UNSUPPORTED_METER"
            : missingPrice ? "PRICE_RATE_NOT_CONFIGURED" : "USAGE_INCOMPLETE";
        return new ModelPriceCalculation(all ? "priced" : "partial", reason, rule.definition().getId(),
            rule.definition().getName(), book.version().getCurrency(), total, List.copyOf(items));
    }

    private ModelPriceRateRow findRate(ModelPriceBook.Rule rule, String meter, int ttl) {
        return rule.rates().stream().filter(rate -> rate.getMeter().equals(meter) && rate.getCacheTtlSeconds() == ttl)
            .findFirst().orElseGet(() -> ttl == 0 ? null : rule.rates().stream()
                .filter(rate -> rate.getMeter().equals(meter) && rate.getCacheTtlSeconds() == 0).findFirst().orElse(null));
    }

    private ModelPriceCalculation unavailable(ModelPriceBook book, String status, String reason) {
        return new ModelPriceCalculation(status, reason, null, null, book == null ? null : book.version().getCurrency(), null, List.of());
    }

    private record Quantity(String meter, int ttl, Long count) {
    }
}
