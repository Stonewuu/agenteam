package com.stonewu.agenteam.mapper.modelpricing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rate;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Time;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.modelpricing.LlmAbacusCatalogClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 LLM Abacus 公开目录中的原生人民币（CNY）文本模型价格。 */
@Component
public class LlmAbacusCatalogParser {
    public static final String SOURCE_ID = "llmabacus";
    public static final String SOURCE_NAME = "LLM Abacus";
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");
    private static final Map<String, String> DEFAULT_APIS = Map.ofEntries(
        Map.entry("deepseek", "https://api.deepseek.com/v1"),
        Map.entry("alibaba", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Map.entry("zhipu", "https://open.bigmodel.cn/api/paas/v4"),
        Map.entry("moonshot", "https://api.moonshot.cn/v1"),
        Map.entry("bytedance", "https://ark.cn-beijing.volces.com/api/v3"),
        Map.entry("baidu", "https://qianfan.baidubce.com/v2"),
        Map.entry("tencent", "https://api.hunyuan.cloud.tencent.com/v1"),
        Map.entry("minimax", "https://api.minimaxi.com/v1"),
        Map.entry("stepfun", "https://api.stepfun.com/v1"),
        Map.entry("baichuan", "https://api.baichuan-ai.com/v1"),
        Map.entry("xfyun", "https://spark-api-open.xf-yun.com/v1")
    );
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(24).maxStringLength(16384).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public List<ModelPriceCatalogEntry> read(InputStream input) throws IOException {
        JsonNode root = json.readTree(input);
        require(root != null && root.isObject());
        JsonNode modelsNode = root.get("models");
        require(modelsNode != null && modelsNode.isArray() && modelsNode.size() <= 5000);
        Map<String, String> vendors = readVendors(root.get("vendors"));
        var result = new ArrayList<ModelPriceCatalogEntry>();
        for (JsonNode model : modelsNode) {
            if (model == null || !model.isObject()) {
                continue;
            }
            ModelPriceCatalogEntry entry = parseModel(model, vendors);
            if (entry != null) {
                result.add(entry);
            }
        }
        require(!result.isEmpty());
        return List.copyOf(result);
    }

    private Map<String, String> readVendors(JsonNode vendorsNode) throws IOException {
        var vendors = new HashMap<String, String>();
        if (vendorsNode == null || !vendorsNode.isArray()) {
            return vendors;
        }
        require(vendorsNode.size() <= 512);
        for (JsonNode vendor : vendorsNode) {
            if (vendor != null && vendor.isObject()) {
                String id = optionalText(vendor.get("id"), 120);
                String name = optionalText(vendor.get("name"), 200);
                if (id != null && name != null) {
                    vendors.put(id, name);
                }
            }
        }
        return vendors;
    }

    private ModelPriceCatalogEntry parseModel(JsonNode model, Map<String, String> vendors) {
        if (model.path("retired").asBoolean(false)) {
            return null;
        }
        String currency = optionalText(model.get("priceCurrency"), 16);
        if (currency == null) {
            currency = optionalText(model.get("billing_currency"), 16);
        }
        if (!"CNY".equals(currency)) {
            return null;
        }
        if (!hasTextModality(model.get("modality"))) {
            return null;
        }
        String modelId = optionalText(model.get("id"), 256);
        String vendorId = optionalText(model.get("vendorId"), 120);
        if (vendorId == null) {
            vendorId = optionalText(model.get("vendor_id"), 120);
        }
        if (modelId == null || vendorId == null) {
            return null;
        }
        String modelName = optionalText(model.get("name"), 256);
        if (modelName == null) {
            modelName = modelId;
        }
        String vendorName = vendors.getOrDefault(vendorId, optionalText(model.get("vendor_name"), 200));
        if (vendorName == null) {
            vendorName = vendorId;
        }
        var notices = new TreeSet<String>();
        List<Rate> baseRates = extractRates(model, "inputPrice", "outputPrice", "cachedInputPrice");
        if (baseRates == null) {
            baseRates = extractRates(model, "input_price_cny_per_m", "output_price_cny_per_m", "cached_input_price_cny_per_m");
        }
        if (baseRates == null) {
            return null;
        }
        String timezone = "Asia/Shanghai";
        List<Rule> rules = buildRules(model, baseRates, notices);
        if (rules == null) {
            return null;
        }
        String candidateTimezone = optionalText(model.path("timePricing").get("timezone"), 64);
        if (candidateTimezone != null && ZoneId.getAvailableZoneIds().contains(candidateTimezone)) {
            timezone = candidateTimezone;
        }
        if (hasAudioModality(model.get("modality"))) {
            notices.add(Messages.source("text.modelpricing.modelsDevPriceMapper.notice.1"));
        }
        String sourceReference = cleanSourceUrl(optionalText(model.get("source"), 2048));
        if (sourceReference == null) {
            sourceReference = LlmAbacusCatalogClient.SOURCE;
        }
        String catalogProviderId = SOURCE_ID + ":" + vendorId;
        return new ModelPriceCatalogEntry(
            SOURCE_ID,
            SOURCE_NAME,
            sourceReference,
            "CNY",
            timezone,
            catalogProviderId,
            vendorName,
            DEFAULT_APIS.get(vendorId),
            modelId,
            modelName,
            modelId,
            List.copyOf(rules),
            List.copyOf(notices)
        );
    }

    private List<Rule> buildRules(JsonNode model, List<Rate> baseRates, Set<String> notices) {
        JsonNode tiers = model.get("tiers");
        if (tiers == null || tiers.isNull()) {
            tiers = model.get("price_tiers");
        }
        if (tiers != null && tiers.isArray() && !tiers.isEmpty()) {
            return buildTierRules(tiers);
        }
        JsonNode timePricing = model.get("timePricing");
        if (timePricing != null && timePricing.isObject()) {
            List<Rule> timed = buildTimeRules(timePricing, baseRates, notices);
            if (timed != null) {
                return timed;
            }
        }
        return List.of(new Rule(Messages.source("text.modelpricing.modelsDevPriceMapper.map.1"),
            0, true, 0, null, "standard", List.of(), List.of(), baseRates));
    }

    private List<Rule> buildTierRules(JsonNode tiersNode) {
        if (tiersNode.size() > 32) {
            return null;
        }
        record TierSlice(Long upTo, List<Rate> rates) {
        }
        var slices = new ArrayList<TierSlice>();
        var boundaries = new HashSet<Long>();
        boolean hasUnlimited = false;
        for (JsonNode tier : tiersNode) {
            if (tier == null || !tier.isObject()) {
                return null;
            }
            List<Rate> rates = extractRates(tier, "inputPrice", "outputPrice", "cachedInputPrice");
            if (rates == null) {
                rates = extractRates(tier, "input_price_cny_per_m", "output_price_cny_per_m", "cached_input_price_cny_per_m");
            }
            if (rates == null) {
                return null;
            }
            JsonNode upToNode = tier.has("upTo") ? tier.get("upTo") : tier.get("up_to_input_tokens");
            Long upTo = null;
            if (upToNode != null && !upToNode.isNull()) {
                if (!upToNode.isIntegralNumber() || !upToNode.canConvertToLong()) {
                    return null;
                }
                long value = upToNode.longValue();
                if (value <= 0 || value >= 100000000000L || !boundaries.add(value)) {
                    return null;
                }
                upTo = value;
            } else {
                if (hasUnlimited) {
                    return null;
                }
                hasUnlimited = true;
            }
            slices.add(new TierSlice(upTo, rates));
        }
        slices.sort(Comparator.comparing(slice -> slice.upTo() == null ? Long.MAX_VALUE : slice.upTo()));
        var rules = new ArrayList<Rule>();
        rules.add(new Rule(Messages.source("text.modelpricing.modelsDevPriceMapper.map.1"),
            0, true, 0, null, "standard", List.of(), List.of(), slices.getFirst().rates()));
        for (int index = 1; index < slices.size(); index++) {
            Long prevUpTo = slices.get(index - 1).upTo();
            if (prevUpTo == null) {
                return null;
            }
            Long currentUpTo = slices.get(index).upTo();
            Long upper = currentUpTo == null ? null : currentUpTo + 1;
            rules.add(new Rule(Messages.source("text.modelpricing.modelsDevPriceMapper.map.2", prevUpTo),
                10, false, prevUpTo + 1, upper, "standard", List.of(), List.of(), slices.get(index).rates()));
        }
        return rules;
    }

    private List<Rule> buildTimeRules(JsonNode timePricing, List<Rate> fallbackRates, Set<String> notices) {
        JsonNode bands = timePricing.get("bands");
        if (bands == null || !bands.isArray() || bands.isEmpty() || bands.size() > 8) {
            notices.add(Messages.source("text.modelpricing.llmAbacusCatalogParser.timeFallback.1"));
            return null;
        }
        String defaultBandId = optionalText(timePricing.get("defaultBand"), 64);
        List<Rate> baseRates = fallbackRates;
        for (JsonNode band : bands) {
            if (band != null && band.isObject() && defaultBandId != null
                && defaultBandId.equals(optionalText(band.get("id"), 64))) {
                List<Rate> matched = extractRates(band, "inputPrice", "outputPrice", "cachedInputPrice");
                if (matched != null) {
                    baseRates = matched;
                }
            }
        }
        var rules = new ArrayList<Rule>();
        rules.add(new Rule(Messages.source("text.modelpricing.modelsDevPriceMapper.map.1"),
            0, true, 0, null, "standard", List.of(), List.of(), baseRates));
        var usedNames = new HashSet<String>();
        usedNames.add(rules.getFirst().name().toLowerCase(Locale.ROOT));
        var occupiedRanges = new ArrayList<int[]>();
        for (JsonNode band : bands) {
            if (band == null || !band.isObject()) {
                continue;
            }
            String bandId = optionalText(band.get("id"), 64);
            if (defaultBandId != null && defaultBandId.equals(bandId)) {
                continue;
            }
            List<Rate> bandRates = extractRates(band, "inputPrice", "outputPrice", "cachedInputPrice");
            String schedule = optionalText(band.get("schedule"), 256);
            if (bandRates == null || schedule == null) {
                notices.add(Messages.source("text.modelpricing.llmAbacusCatalogParser.timeFallback.1"));
                return null;
            }
            List<int[]> dailyRanges = parseDailyRanges(schedule);
            if (dailyRanges == null || dailyRanges.isEmpty() || dailyRanges.size() * 7 > 64) {
                notices.add(Messages.source("text.modelpricing.llmAbacusCatalogParser.timeFallback.1"));
                return null;
            }
            for (int[] candidate : dailyRanges) {
                for (int[] existing : occupiedRanges) {
                    if (candidate[0] < existing[1] && existing[0] < candidate[1]) {
                        notices.add(Messages.source("text.modelpricing.llmAbacusCatalogParser.timeFallback.1"));
                        return null;
                    }
                }
            }
            occupiedRanges.addAll(dailyRanges);
            var times = new ArrayList<Time>();
            for (int weekday = 1; weekday <= 7; weekday++) {
                for (int[] range : dailyRanges) {
                    times.add(new Time(weekday, range[0], range[1]));
                }
            }
            String label = optionalText(band.get("label"), 64);
            if (label == null) {
                label = bandId != null ? bandId : "band";
            }
            String ruleName = Messages.source("text.modelpricing.llmAbacusCatalogParser.timeRule.1", label);
            if (ruleName.length() > 96 || !usedNames.add(ruleName.toLowerCase(Locale.ROOT))) {
                notices.add(Messages.source("text.modelpricing.llmAbacusCatalogParser.timeFallback.1"));
                return null;
            }
            rules.add(new Rule(ruleName, 10, false, 0, null, "standard", List.copyOf(times), List.of(), bandRates));
        }
        return rules;
    }

    private List<int[]> parseDailyRanges(String schedule) {
        var ranges = new ArrayList<int[]>();
        Matcher matcher = TIME_RANGE.matcher(schedule);
        while (matcher.find()) {
            int startHour = Integer.parseInt(matcher.group(1));
            int startMin = Integer.parseInt(matcher.group(2));
            int endHour = Integer.parseInt(matcher.group(3));
            int endMin = Integer.parseInt(matcher.group(4));
            if (startHour > 23 || startMin > 59 || endHour > 24 || endMin > 59 || (endHour == 24 && endMin > 0)) {
                return null;
            }
            int start = startHour * 60 + startMin;
            int end = endHour * 60 + endMin;
            if (start >= end) {
                return null;
            }
            ranges.add(new int[]{start, end});
        }
        ranges.sort(Comparator.comparingInt(item -> item[0]));
        for (int index = 1; index < ranges.size(); index++) {
            if (ranges.get(index)[0] < ranges.get(index - 1)[1]) {
                return null;
            }
        }
        return ranges;
    }

    private List<Rate> extractRates(JsonNode node, String inputKey, String outputKey, String cacheReadKey) {
        String input = validPrice(node.get(inputKey));
        String output = validPrice(node.get(outputKey));
        if (input == null || output == null) {
            return null;
        }
        var rates = new ArrayList<Rate>();
        rates.add(new Rate("input_uncached", 0, input));
        rates.add(new Rate("output", 0, output));
        JsonNode cacheReadNode = node.get(cacheReadKey);
        if (cacheReadNode != null && !cacheReadNode.isNull()) {
            String cacheRead = validPrice(cacheReadNode);
            if (cacheRead == null) {
                return null;
            }
            rates.add(new Rate("cache_read", 0, cacheRead));
        }
        return List.copyOf(rates);
    }

    private String validPrice(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        BigDecimal amount = value.decimalValue().stripTrailingZeros();
        if (amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(1000000)) > 0 || amount.scale() > 12) {
            return null;
        }
        if (amount.signum() == 0) {
            return "0";
        }
        return amount.toPlainString();
    }

    private boolean hasTextModality(JsonNode modality) {
        if (modality == null || !modality.isArray()) {
            return false;
        }
        for (JsonNode item : modality) {
            if ("text".equalsIgnoreCase(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAudioModality(JsonNode modality) {
        if (modality == null || !modality.isArray()) {
            return false;
        }
        for (JsonNode item : modality) {
            if ("audio".equalsIgnoreCase(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private String cleanSourceUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private String optionalText(JsonNode node, int maximum) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.textValue().trim();
        if (value.isEmpty() || value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return value;
    }

    private void require(boolean condition) throws IOException {
        if (!condition) {
            throw new IOException(Messages.text("error.modelpricing.llmAbacusCatalogParser.require.1"));
        }
    }
}
