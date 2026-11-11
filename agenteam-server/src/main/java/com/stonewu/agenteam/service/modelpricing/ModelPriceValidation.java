package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.DateCondition;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rate;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Time;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 保存时规范化时间；发布时再检查完整覆盖及同优先级冲突。
 */
@Component
public class ModelPriceValidation {
    private static final Set<String> METERS = Set.of("input_uncached", "output", "cache_read", "cache_write");
    private static final Set<String> TIERS = Set.of("standard", "flex", "priority", "scale", "batch", "fast");

    public ModelPriceConfiguration normalize(ModelPriceConfiguration value) {
        if (value == null || value.currency() == null || !value.currency().matches("[A-Z]{3}")) {
            throw invalid("currency", Messages.text("error.modelpricing.modelPriceValidation.normalize.1"));
        }
        try {
            Currency.getInstance(value.currency());
        } catch (IllegalArgumentException failure) {
            throw invalid("currency", Messages.text("error.modelpricing.modelPriceValidation.normalize.1"));
        }
        if (value.timezone() == null || !ZoneId.getAvailableZoneIds().contains(value.timezone())) {
            throw invalid("timezone", Messages.text("error.auth.accountInputValidation.timezone.1"));
        }
        reference(value.sourceReference());
        String note = text(value.note(), 500, "note", false);
        if (value.rules() == null || value.rules().size() > 100) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.2"));
        }
        var rules = new ArrayList<Rule>();
        for (var rule : value.rules()) {
            if (rule == null) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.3"));
            }
            String name = text(rule.name(), 100, "rules", true);
            if (rule.priority() < 0 || rule.priority() > 1000 || rule.defaultRule() != (rule.priority() == 0)) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.4"));
            }
            if (rule.contextMinTokens() < 0 || rule.contextMinTokens() > 100000000000L
                || (rule.contextMaxTokens() != null && (rule.contextMaxTokens() <= rule.contextMinTokens()
                    || rule.contextMaxTokens() > 100000000001L))) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.5"));
            }
            if (!TIERS.contains(rule.serviceTier() == null ? "" : rule.serviceTier())) {
                throw invalid("rules", Messages.text("error.modelprofile.modelProtocolOptions.serviceTier.1"));
            }
            var rates = rates(rule.rates());
            var dates = dates(rule.dates());
            if (!dates.isEmpty() && (value.calendarValidThrough() == null || dates.stream()
                .anyMatch(date -> date.localDate().isAfter(value.calendarValidThrough())))) {
                throw invalid("calendarValidThrough", Messages.text("error.modelpricing.modelPriceValidation.normalize.6"));
            }
            var today = new ArrayList<Time>();
            var tomorrow = new ArrayList<Time>();
            if (rule.times() == null || rule.times().size() > 64) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.7"));
            }
            for (var time : rule.times()) {
                if (time == null || time.weekday() < 1 || time.weekday() > 7 || time.startMinute() < 0
                    || time.startMinute() > 1439 || time.endMinute() < 0 || time.endMinute() > 1440
                    || time.startMinute() == time.endMinute()) {
                    throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.8"));
                }
                if (time.endMinute() > time.startMinute()) {
                    today.add(time);
                } else {
                    today.add(new Time(time.weekday(), time.startMinute(), 1440));
                    if (time.endMinute() > 0) {
                        tomorrow.add(new Time(time.weekday() % 7 + 1, 0, time.endMinute()));
                    }
                }
            }
            rules.add(copy(rule, name, merge(today), dates, rates));
            if (!tomorrow.isEmpty()) {
                if (name.length() > 96) {
                    throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.9"));
                }
                var shifted = dates.stream().map(date -> new DateCondition(date.localDate().plusDays(1), date.kind(), date.note())).toList();
                if (value.calendarValidThrough() != null && shifted.stream()
                    .anyMatch(date -> date.localDate().isAfter(value.calendarValidThrough()))) {
                    throw invalid("calendarValidThrough", Messages.text("error.modelpricing.modelPriceValidation.normalize.10"));
                }
                rules.add(copy(rule, Messages.text("text.modelpricing.modelPriceValidation.normalize.1", name), merge(tomorrow), shifted, rates));
            }
        }
        var names = new HashSet<String>();
        if (rules.size() > 100 || rules.stream().mapToInt(rule -> rule.dates().size()).sum() > 2000) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.11"));
        }
        for (var rule : rules) {
            if (!names.add(rule.name().toLowerCase(Locale.ROOT))) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.normalize.12"));
            }
        }
        return new ModelPriceConfiguration(value.currency(), value.timezone(), value.calendarValidThrough(),
            value.sourceReference(), value.sourceCheckedAt(), note, List.copyOf(rules));
    }

    public void requirePublishable(ModelPriceConfiguration value) {
        var defaults = value.rules().stream().filter(Rule::defaultRule).toList();
        if (defaults.size() != 1) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.requirePublishable.1"));
        }
        var base = defaults.getFirst();
        if (!base.serviceTier().equals("standard") || base.contextMinTokens() != 0 || base.contextMaxTokens() != null
            || !base.times().isEmpty() || !base.dates().isEmpty()) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.requirePublishable.2"));
        }
        for (var rule : value.rules()) {
            if (rule.rates().isEmpty()) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.requirePublishable.3", rule.name()));
            }
        }
        ModelPriceCoverage.validate(value.rules());
    }

    private List<Rate> rates(List<Rate> values) {
        if (values == null || values.size() > 32) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.rates.1"));
        }
        var keys = new HashSet<String>();
        for (var rate : values) {
            if (rate == null || !METERS.contains(rate.meter() == null ? "" : rate.meter())
                || rate.cacheTtlSeconds() < 0 || rate.cacheTtlSeconds() > 31536000
                || (!rate.meter().equals("cache_write") && rate.cacheTtlSeconds() != 0)) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.rates.2"));
            }
            if (!keys.add(rate.meter() + ":" + rate.cacheTtlSeconds())) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.rates.3"));
            }
            if (rate.pricePerMillion() == null || !rate.pricePerMillion().matches("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,12})?")
                || rate.decimalPrice().compareTo(BigDecimal.valueOf(1000000)) > 0) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.rates.4"));
            }
        }
        return List.copyOf(values);
    }

    private List<DateCondition> dates(List<DateCondition> values) {
        if (values == null || values.size() > 2000) {
            throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.dates.1"));
        }
        var keys = new HashSet<String>();
        for (var date : values) {
            if (date == null || date.localDate() == null || !Set.of("include", "exclude").contains(date.kind() == null ? "" : date.kind())
                || !keys.add(date.localDate().toString())) {
                throw invalid("rules", Messages.text("error.modelpricing.modelPriceValidation.dates.2"));
            }
            text(date.note(), 100, "rules", false);
        }
        return List.copyOf(values);
    }

    private List<Time> merge(List<Time> times) {
        var sorted = times.stream().sorted(Comparator.comparingInt(Time::weekday).thenComparingInt(Time::startMinute)).toList();
        var result = new ArrayList<Time>();
        for (var time : sorted) {
            if (!result.isEmpty() && result.getLast().weekday() == time.weekday() && result.getLast().endMinute() >= time.startMinute()) {
                var previous = result.removeLast();
                result.add(new Time(time.weekday(), previous.startMinute(), Math.max(previous.endMinute(), time.endMinute())));
            } else {
                result.add(time);
            }
        }
        return List.copyOf(result);
    }

    private Rule copy(Rule original, String name, List<Time> times, List<DateCondition> dates, List<Rate> rates) {
        return new Rule(name, original.priority(), original.defaultRule(), original.contextMinTokens(), original.contextMaxTokens(),
            original.serviceTier(), times, dates, rates);
    }

    private void reference(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            var uri = URI.create(value);
            if (value.length() > 2048 || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw invalid("sourceReference", Messages.text("error.modelpricing.modelPriceValidation.reference.1"));
        }
    }

    private String text(String value, int limit, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field, Messages.text("error.modelpricing.modelPriceValidation.text.1"));
            }
            return null;
        }
        if (value.length() > limit || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field, Messages.text("error.modelpricing.modelPriceValidation.text.2"));
        }
        return value.strip();
    }

    private ApiException invalid(String field, String message) {
        return ApiException.invalidField(field, message);
    }
}
