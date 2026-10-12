package com.stonewu.agenteam.mapper.modelpricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rate;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 只转换语义明确的文本单价；没有有效期的缓存创建价不能作为所有缓存时长的通用价。 */
@Component
public class ModelsDevPriceMapper {
    public static final String CACHE_NOTICE = "来源未注明缓存创建价格的有效期，该项未导入；如有缓存创建用量，请在草稿中补充。";
    private static final Map<String, String> METERS = Map.of("input", "input_uncached", "output", "output", "cache_read", "cache_read");
    private static final List<String> FIELDS = List.of("input", "output", "cache_read");

    public Prices map(JsonNode cost) {
        if (!validCost(cost)) {
            return null;
        }
        var notices = new TreeSet<String>();
        var rules = new ArrayList<Rule>();
        rules.add(rule("基础价格", 0, true, 0, null, cost));
        notice(cost, notices);
        var tiers = new ArrayList<JsonNode>();
        if (cost.has("tiers")) {
            if (!cost.path("tiers").isArray() || cost.path("tiers").size() > 32) {
                return null;
            }
            cost.path("tiers").forEach(tiers::add);
        } else if (cost.has("context_over_200k")) {
            var legacy = cost.path("context_over_200k").deepCopy();
            if (!legacy.isObject()) {
                return null;
            }
            ((ObjectNode) legacy).putObject("tier").put("type", "context").put("size", 200000);
            tiers.add(legacy);
        }
        var boundaries = new TreeSet<Long>();
        for (var tier : tiers) {
            var size = tier.path("tier").path("size");
            if (!validCost(tier) || !tier.path("tier").path("type").asText("context").equals("context")
                || !size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0
                || size.longValue() >= 100000000000L || !boundaries.add(size.longValue())) {
                return null;
            }
        }
        tiers.sort(Comparator.comparingLong(value -> value.path("tier").path("size").longValue()));
        for (int index = 0; index < tiers.size(); index++) {
            var tier = tiers.get(index);
            long threshold = tier.path("tier").path("size").longValue();
            Long upper = index + 1 < tiers.size() ? tiers.get(index + 1).path("tier").path("size").longValue() + 1 : null;
            rules.add(rule("上下文超过 " + threshold + " Token", 10, false, threshold + 1, upper, tier));
            notice(tier, notices);
        }
        return new Prices(List.copyOf(rules), List.copyOf(notices));
    }

    private boolean validCost(JsonNode cost) {
        if (cost == null || !cost.isObject() || !validPrice(cost.get("input")) || !validPrice(cost.get("output"))) {
            return false;
        }
        for (String field : List.of("cache_read", "cache_write", "reasoning", "input_audio", "output_audio")) {
            if (cost.has(field) && !validPrice(cost.get(field))) {
                return false;
            }
        }
        // 平台的输出总量包含思考数量，不能用不同的独立思考单价替代输出价。
        return !cost.has("reasoning") || cost.path("reasoning").decimalValue().compareTo(cost.path("output").decimalValue()) == 0;
    }

    private boolean validPrice(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        BigDecimal amount = value.decimalValue().stripTrailingZeros();
        return amount.signum() >= 0 && amount.compareTo(BigDecimal.valueOf(1000000)) <= 0 && amount.scale() <= 12;
    }

    private Rule rule(String name, int priority, boolean base, long lower, Long upper, JsonNode cost) {
        var rates = new ArrayList<Rate>();
        for (String field : FIELDS) {
            if (cost.has(field)) {
                rates.add(new Rate(METERS.get(field), 0, cost.path(field).decimalValue().stripTrailingZeros().toPlainString()));
            }
        }
        return new Rule(name, priority, base, lower, upper, "standard", List.of(), List.of(), List.copyOf(rates));
    }

    private void notice(JsonNode cost, TreeSet<String> notices) {
        if (cost.has("cache_write")) {
            notices.add(CACHE_NOTICE);
        }
        if (cost.has("input_audio") || cost.has("output_audio")) {
            notices.add("本次仅导入文本价格，不包含音频价格。");
        }
    }

    public record Prices(List<Rule> rules, List<String> notices) {
    }
}
