package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.DateCondition;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Time;
import com.stonewu.agenteam.service.http.ApiException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按上下文边界、星期时间和实际日期检查冲突与遗漏，不按每个 Token 枚举。
 */
final class ModelPriceCoverage {
    private ModelPriceCoverage() {
    }

    static void validate(List<Rule> rules) {
        for (int left = 0; left < rules.size(); left++) {
            for (int right = left + 1; right < rules.size(); right++) {
                var a = rules.get(left);
                var b = rules.get(right);
                if (a.priority() == b.priority() && a.serviceTier().equals(b.serviceTier())
                    && a.contextMinTokens() < upper(b) && b.contextMinTokens() < upper(a) && datesOverlap(a, b)) {
                    throw ApiException.invalidField("rules", "同优先级规则“" + a.name() + "”与“" + b.name() + "”存在重叠。");
                }
            }
        }
        for (String tier : rules.stream().map(Rule::serviceTier).distinct().toList()) {
            requireCoverage(rules.stream().filter(rule -> rule.serviceTier().equals(tier)).toList(), tier);
        }
    }

    private static boolean datesOverlap(Rule a, Rule b) {
        var includesA = includes(a);
        var includesB = includes(b);
        if (!includesA.isEmpty() || !includesB.isEmpty()) {
            var candidates = new HashSet<>(includesA.isEmpty() ? includesB : includesA);
            if (!includesA.isEmpty() && !includesB.isEmpty()) {
                candidates.retainAll(includesB);
            }
            return candidates.stream().filter(date -> permitsDate(a, date) && permitsDate(b, date))
                .anyMatch(date -> timesOverlap(a, b, date.getDayOfWeek().getValue()));
        }
        for (int day = 1; day <= 7; day++) {
            if (timesOverlap(a, b, day)) {
                return true;
            }
        }
        return false;
    }

    private static boolean timesOverlap(Rule a, Rule b, int day) {
        return times(a, day).stream().anyMatch(x -> times(b, day).stream()
            .anyMatch(y -> x.startMinute() < y.endMinute() && y.startMinute() < x.endMinute()));
    }

    private static void requireCoverage(List<Rule> rules, String tier) {
        var bounds = new TreeSet<Long>();
        bounds.add(0L);
        var dates = new HashSet<LocalDate>();
        for (var rule : rules) {
            bounds.add(rule.contextMinTokens());
            if (rule.contextMaxTokens() != null && rule.contextMaxTokens() <= 100000000000L) {
                bounds.add(rule.contextMaxTokens());
            }
            rule.dates().stream().map(DateCondition::localDate).forEach(dates::add);
        }
        for (long input : bounds) {
            var candidates = rules.stream().filter(rule -> input >= rule.contextMinTokens() && input < upper(rule)).toList();
            for (int day = 1; day <= 7; day++) {
                int weekday = day;
                var regular = candidates.stream().filter(rule -> includes(rule).isEmpty()).toList();
                if (!coversDay(regular, weekday)) {
                    throw missing(tier, input, "星期" + day);
                }
            }
            for (var date : dates) {
                if (!coversDay(candidates.stream().filter(rule -> permitsDate(rule, date)).toList(), date.getDayOfWeek().getValue())) {
                    throw missing(tier, input, date.toString());
                }
            }
        }
    }

    private static boolean coversDay(List<Rule> rules, int day) {
        var ranges = new ArrayList<Time>();
        rules.forEach(rule -> ranges.addAll(times(rule, day)));
        ranges.sort(Comparator.comparingInt(Time::startMinute));
        int end = 0;
        for (var time : ranges) {
            if (time.startMinute() > end) {
                return false;
            }
            end = Math.max(end, time.endMinute());
        }
        return end == 1440;
    }

    private static ApiException missing(String tier, long input, String day) {
        return ApiException.invalidField("rules", "服务档位 " + tier + " 在输入量 " + input + "、" + day + " 未覆盖全天价格。");
    }

    private static boolean permitsDate(Rule rule, LocalDate date) {
        var included = includes(rule);
        return (included.isEmpty() || included.contains(date)) && rule.dates().stream()
            .noneMatch(value -> value.kind().equals("exclude") && value.localDate().equals(date));
    }

    private static Set<LocalDate> includes(Rule rule) {
        var result = new HashSet<LocalDate>();
        rule.dates().stream().filter(date -> date.kind().equals("include")).map(DateCondition::localDate).forEach(result::add);
        return result;
    }

    private static List<Time> times(Rule rule, int day) {
        return rule.times().isEmpty() ? List.of(new Time(day, 0, 1440))
            : rule.times().stream().filter(time -> time.weekday() == day).toList();
    }

    private static long upper(Rule rule) {
        return rule.contextMaxTokens() == null ? Long.MAX_VALUE : rule.contextMaxTokens();
    }
}
