package com.stonewu.agenteam.service.modelpricing;

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
            throw invalid("currency", "请选择有效币种。");
        }
        try {
            Currency.getInstance(value.currency());
        } catch (IllegalArgumentException failure) {
            throw invalid("currency", "请选择有效币种。");
        }
        if (value.timezone() == null || !ZoneId.getAvailableZoneIds().contains(value.timezone())) {
            throw invalid("timezone", "请选择有效时区。");
        }
        reference(value.sourceReference());
        String note = text(value.note(), 500, "note", false);
        if (value.rules() == null || value.rules().size() > 100) {
            throw invalid("rules", "每版价格最多配置 100 条规则。");
        }
        var rules = new ArrayList<Rule>();
        for (var rule : value.rules()) {
            if (rule == null) {
                throw invalid("rules", "价格规则不能为空。");
            }
            String name = text(rule.name(), 100, "rules", true);
            if (rule.priority() < 0 || rule.priority() > 1000 || rule.defaultRule() != (rule.priority() == 0)) {
                throw invalid("rules", "基础规则的优先级为 0，其他规则为 1 至 1000。");
            }
            if (rule.contextMinTokens() < 0 || rule.contextMinTokens() > 100000000000L
                || (rule.contextMaxTokens() != null && (rule.contextMaxTokens() <= rule.contextMinTokens()
                    || rule.contextMaxTokens() > 100000000001L))) {
                throw invalid("rules", "上下文上界必须大于下界，最大数量为一千亿。");
            }
            if (!TIERS.contains(rule.serviceTier() == null ? "" : rule.serviceTier())) {
                throw invalid("rules", "请选择支持的服务档位。");
            }
            var rates = rates(rule.rates());
            var dates = dates(rule.dates());
            if (!dates.isEmpty() && (value.calendarValidThrough() == null || dates.stream()
                .anyMatch(date -> date.localDate().isAfter(value.calendarValidThrough())))) {
                throw invalid("calendarValidThrough", "请填写日期规则的覆盖截止日期，且不能早于已配置日期。");
            }
            var today = new ArrayList<Time>();
            var tomorrow = new ArrayList<Time>();
            if (rule.times() == null || rule.times().size() > 64) {
                throw invalid("rules", "每条规则最多配置 64 个时间段。");
            }
            for (var time : rule.times()) {
                if (time == null || time.weekday() < 1 || time.weekday() > 7 || time.startMinute() < 0
                    || time.startMinute() > 1439 || time.endMinute() < 0 || time.endMinute() > 1440
                    || time.startMinute() == time.endMinute()) {
                    throw invalid("rules", "请填写有效的星期、开始时间和结束时间。");
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
                    throw invalid("rules", "跨午夜规则的名称最多为 96 个字符。");
                }
                var shifted = dates.stream().map(date -> new DateCondition(date.localDate().plusDays(1), date.kind(), date.note())).toList();
                if (value.calendarValidThrough() != null && shifted.stream()
                    .anyMatch(date -> date.localDate().isAfter(value.calendarValidThrough()))) {
                    throw invalid("calendarValidThrough", "跨午夜规则的次日也必须在日历覆盖范围内。");
                }
                rules.add(copy(rule, name + "（次日）", merge(tomorrow), shifted, rates));
            }
        }
        var names = new HashSet<String>();
        if (rules.size() > 100 || rules.stream().mapToInt(rule -> rule.dates().size()).sum() > 2000) {
            throw invalid("rules", "拆分跨午夜时间后，每版最多 100 条规则和 2000 个指定日期。");
        }
        for (var rule : rules) {
            if (!names.add(rule.name().toLowerCase(Locale.ROOT))) {
                throw invalid("rules", "价格规则名称不能重复。");
            }
        }
        return new ModelPriceConfiguration(value.currency(), value.timezone(), value.calendarValidThrough(),
            value.sourceReference(), value.sourceCheckedAt(), note, List.copyOf(rules));
    }

    public void requirePublishable(ModelPriceConfiguration value) {
        var defaults = value.rules().stream().filter(Rule::defaultRule).toList();
        if (defaults.size() != 1) {
            throw invalid("rules", "请配置且只配置一条基础价格规则。");
        }
        var base = defaults.getFirst();
        if (!base.serviceTier().equals("standard") || base.contextMinTokens() != 0 || base.contextMaxTokens() != null
            || !base.times().isEmpty() || !base.dates().isEmpty()) {
            throw invalid("rules", "基础价格必须覆盖标准服务的全部上下文、星期和时间。");
        }
        for (var rule : value.rules()) {
            if (rule.rates().isEmpty()) {
                throw invalid("rules", "规则“" + rule.name() + "”尚未填写单价。");
            }
        }
        ModelPriceCoverage.validate(value.rules());
    }

    private List<Rate> rates(List<Rate> values) {
        if (values == null || values.size() > 32) {
            throw invalid("rules", "每条规则最多配置 32 项单价。");
        }
        var keys = new HashSet<String>();
        for (var rate : values) {
            if (rate == null || !METERS.contains(rate.meter() == null ? "" : rate.meter())
                || rate.cacheTtlSeconds() < 0 || rate.cacheTtlSeconds() > 31536000
                || (!rate.meter().equals("cache_write") && rate.cacheTtlSeconds() != 0)) {
                throw invalid("rules", "计量项或缓存有效期不正确。");
            }
            if (!keys.add(rate.meter() + ":" + rate.cacheTtlSeconds())) {
                throw invalid("rules", "同一计量项和缓存有效期的单价不能重复。");
            }
            if (rate.pricePerMillion() == null || !rate.pricePerMillion().matches("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,12})?")
                || rate.decimalPrice().compareTo(BigDecimal.valueOf(1000000)) > 0) {
                throw invalid("rules", "每百万单价须为 0 至 1000000，最多保留 12 位小数。");
            }
        }
        return List.copyOf(values);
    }

    private List<DateCondition> dates(List<DateCondition> values) {
        if (values == null || values.size() > 2000) {
            throw invalid("rules", "每版最多配置 2000 个指定日期。");
        }
        var keys = new HashSet<String>();
        for (var date : values) {
            if (date == null || date.localDate() == null || !Set.of("include", "exclude").contains(date.kind() == null ? "" : date.kind())
                || !keys.add(date.localDate().toString())) {
                throw invalid("rules", "指定日期不能重复，且必须选择包含或排除。");
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
            throw invalid("sourceReference", "价格来源请填写不含账号、查询参数或片段的网页地址。");
        }
    }

    private String text(String value, int limit, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field, "请填写价格规则名称。");
            }
            return null;
        }
        if (value.length() > limit || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field, "内容过长或包含无效控制字符。");
        }
        return value.strip();
    }

    private ApiException invalid(String field, String message) {
        return ApiException.invalidField(field, message);
    }
}
