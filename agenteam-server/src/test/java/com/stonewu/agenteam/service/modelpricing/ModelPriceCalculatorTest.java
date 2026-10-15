package com.stonewu.agenteam.service.modelpricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceBookMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceBook;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCalculation;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.DateCondition;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rate;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Time;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelusage.ModelUsageNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import com.stonewu.agenteam.service.modelusage.ModelUsageQueryService;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPriceCalculatorTest {
    private final ModelPriceCalculator calculator = new ModelPriceCalculator();
    private final ModelPriceValidation validation = new ModelPriceValidation();
    private final Instant at = Instant.parse("2026-10-12T01:00:00Z");

    @Test
    void openAiCachesAndReasoningAreChargedExactlyOnce() throws Exception {
        var usage = usage("openai", """
            {"prompt_tokens":10000,"completion_tokens":1500,"total_tokens":11500,
             "prompt_tokens_details":{"cached_tokens":2000,"cache_write_tokens":1000},
             "completion_tokens_details":{"reasoning_tokens":500}}
            """);
        var result = calculate(book(base("2", "8", "0.2", "2.5")), usage);
        assertThat(result.status()).isEqualTo("priced");
        assertThat(result.totalAmount()).isEqualByComparingTo("0.0289");
        assertThat(usage.uncachedInputTokens()).isEqualTo(7000);
    }

    @Test
    void anthropicCacheDurationsSelectSeparateRates() throws Exception {
        var rule = new Rule("基础", 0, true, 0, null, "standard", List.of(), List.of(), List.of(
            rate("input_uncached", "3"), rate("output", "15"), rate("cache_read", "0.3"),
            new Rate("cache_write", 300, new BigDecimal("3.75")), new Rate("cache_write", 3600, new BigDecimal("6"))));
        var usage = usage("anthropic", """
            {"input_tokens":1000,"cache_read_input_tokens":6000,"cache_creation_input_tokens":2500,
             "cache_creation":{"ephemeral_5m_input_tokens":2000,"ephemeral_1h_input_tokens":500},"output_tokens":500}
            """);
        var result = calculate(book(rule), usage);
        assertThat(usage.inputTokens()).isEqualTo(9500);
        assertThat(result.totalAmount()).isEqualByComparingTo("0.0228");
        assertThat(result.items().stream().filter(item -> item.meter().equals("cache_write")))
            .extracting(ModelPriceCalculation.Item::cacheTtlSeconds).containsExactlyInAnyOrder(300, 3600);
    }

    @Test
    void geminiThoughtAndToolInputAreIncludedExactlyOnce() throws Exception {
        var prices = book(base("1", "4", "0.1", "0"));
        for (int toolInput : List.of(0, 300)) {
            var usage = usage("gemini", "{\"promptTokenCount\":12000,\"toolUsePromptTokenCount\":" + toolInput
                + ",\"cachedContentTokenCount\":8000,\"candidatesTokenCount\":900,\"thoughtsTokenCount\":600}");
            assertThat(calculate(prices, usage).totalAmount()).isEqualByComparingTo(toolInput == 0 ? "0.0108" : "0.0111");
        }
    }

    @Test
    void contextThresholdPricesTheWholeRequest() throws Exception {
        var expensive = new Rule("长上下文", 10, false, 200001, null, "standard", List.of(), List.of(),
            base("4", "12", "0", "0").rates());
        var prices = book(base("2", "8", "0", "0"), expensive);
        String[] expected = {"0.407998", "0.408", "0.812004"};
        for (int index = 0; index < 3; index++) {
            assertThat(calculate(prices, simple(199999 + index, 1000)).totalAmount()).isEqualByComparingTo(expected[index]);
        }
    }

    @Test
    void smallestPriceSurvivesWithoutRounding() throws Exception {
        assertThat(calculate(book(base("0.000000000001", "0", "0", "0")), simple(1, 0)).totalAmount())
            .isEqualByComparingTo("0.000000000000000001");
    }

    @Test
    void missingCacheRateOrUsageNeverBecomesFree() throws Exception {
        var rule = new Rule("基础", 0, true, 0, null, "standard", List.of(), List.of(),
            List.of(rate("input_uncached", "2"), rate("output", "8")));
        var withCache = usage("openai", """
            {"prompt_tokens":2000,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":1000,"cache_write_tokens":0}}
            """);
        var result = calculate(book(rule), withCache);
        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.reason()).isEqualTo("PRICE_RATE_NOT_CONFIGURED");
        assertThat(result.totalAmount()).isNull();
        assertThat(result.knownAmount()).isEqualByComparingTo("0.00208");
        assertThat(calculate(book(rule), usage("openai", "{}" )).totalAmount()).isNull();
        assertThat(calculate(null, simple(0, 0)).status()).isEqualTo("unpriced");
    }

    @Test
    void unknownContextDoesNotFallBackPastConditionalRule() throws Exception {
        var tier = new Rule("长上下文", 10, false, 200001, null, "standard", List.of(), List.of(), base("4", "12", "0", "0").rates());
        assertThat(calculate(book(base("2", "8", "0", "0"), tier), usage("openai", "{\"completion_tokens\":10}" )).reason())
            .isEqualTo("CONTEXT_NOT_REPORTED");
    }

    @Test
    void actualTierOverridesRequestedAndAutoMustBeConfirmed() throws Exception {
        var prices = book(base("2", "8", "0", "0"));
        var usage = simple(10, 2);
        assertThat(calculator.calculate(prices, at, "auto", null, usage, true).reason()).isEqualTo("SERVICE_TIER_NOT_REPORTED");
        assertThat(calculator.calculate(prices, at, "default", "priority", usage, true).reason()).isEqualTo("PRICE_RULE_NOT_FOUND");
        assertThat(calculator.calculate(prices, at, "auto", "default", usage, true).status()).isEqualTo("priced");
    }

    @Test
    void overnightDatesAreSplitWithoutMatchingUnrelatedHours() throws Exception {
        var overnight = new Rule("夜间", 10, false, 0, null, "standard", List.of(new Time(1, 1380, 480)),
            List.of(new DateCondition(LocalDate.parse("2026-10-12"), "include", null)), base("1", "1", "0", "0").rates());
        var prices = book(base("2", "8", "0", "0"), overnight);
        var usage = simple(100, 0);
        assertThat(prices.rules()).hasSize(3);
        assertThat(calculator.calculate(prices, Instant.parse("2026-10-12T23:59:00Z"), null, null, usage, true).totalAmount()).isEqualByComparingTo("0.0001");
        assertThat(calculator.calculate(prices, Instant.parse("2026-10-13T07:59:00Z"), null, null, usage, true).totalAmount()).isEqualByComparingTo("0.0001");
        assertThat(calculator.calculate(prices, Instant.parse("2026-10-12T07:59:00Z"), null, null, usage, true).totalAmount()).isEqualByComparingTo("0.0002");
        assertThat(calculator.calculate(prices, Instant.parse("2026-10-13T08:00:00Z"), null, null, usage, true).totalAmount()).isEqualByComparingTo("0.0002");
        assertThat(calculator.calculate(prices, Instant.parse("2027-01-01T00:00:00Z"), null, null, usage, true).reason()).isEqualTo("PRICE_CALENDAR_EXPIRED");
    }

    @Test
    void overlappingPrioritiesAndIncompleteServiceTierAreRejected() {
        var overlap = new Rule("冲突", 10, false, 0, null, "standard", List.of(), List.of(), base("1", "1", "0", "0").rates());
        var other = new Rule("另一条", 10, false, 10, null, "standard", List.of(), List.of(), overlap.rates());
        assertThatThrownBy(() -> book(base("2", "8", "0", "0"), overlap, other)).isInstanceOf(ApiException.class).hasMessageContaining("重叠");
        var priority = new Rule("优先服务", 10, false, 1, null, "priority", List.of(), List.of(), overlap.rates());
        assertThatThrownBy(() -> book(base("2", "8", "0", "0"), priority)).isInstanceOf(ApiException.class).hasMessageContaining("未覆盖");
    }

    @ParameterizedTest(name = "音频输入不使用文本价格：{0}")
    @MethodSource("audioUsage")
    void unsupportedAudioNeverUsesTextRateForThatComponent(String protocol, String payload) throws Exception {
        var usage = usage(protocol, payload);
        var result = calculate(book(base("2", "8", "0", "0")), usage);
        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.items()).extracting(ModelPriceCalculation.Item::meter).containsExactly("output");
        assertThat(result.knownAmount()).isEqualByComparingTo("0.0008");
    }

    private static Stream<Arguments> audioUsage() {
        return Stream.of(
            Arguments.of("openai", """
                {"prompt_tokens":1000,"completion_tokens":100,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0,"audio_tokens":500}}
                """),
            Arguments.of("gemini", """
                {"promptTokenCount":1000,"toolUsePromptTokenCount":0,"cachedContentTokenCount":0,
                 "candidatesTokenCount":100,"thoughtsTokenCount":0,
                 "promptTokensDetails":[{"modality":"AUDIO","tokenCount":500}]}
                """));
    }

    private ModelPriceCalculation calculate(ModelPriceBook book, ModelTokenUsage usage) {
        return calculator.calculate(book, at, null, null, usage, true);
    }

    @Test
    void daylightSavingDaysUseActualBoundariesAndRepeatedLocalHoursKeepSamePrice() throws Exception {
        var spring = ModelUsageQueryService.days(new ModelUsageFilter("enterprise", Instant.parse("2026-03-08T05:00:00Z"),
            Instant.parse("2026-03-09T04:00:00Z"), "America/New_York", null, null, null, null));
        var autumn = ModelUsageQueryService.days(new ModelUsageFilter("enterprise", Instant.parse("2026-11-01T04:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"), "America/New_York", null, null, null, null));
        assertThat(spring).hasSize(1);
        assertThat(autumn).hasSize(1);
        assertThat(Duration.between(spring.getFirst().start(), spring.getFirst().end()).toHours()).isEqualTo(23);
        assertThat(Duration.between(autumn.getFirst().start(), autumn.getFirst().end()).toHours()).isEqualTo(25);
        var rule = new Rule("周日凌晨", 10, false, 0, null, "standard", List.of(new Time(7, 60, 120)), List.of(), base("1", "1", "0", "0").rates());
        var config = validation.normalize(new ModelPriceConfiguration("USD", "America/New_York", null, null, null, null,
            List.of(base("2", "8", "0", "0"), rule)));
        validation.requirePublishable(config);
        var version = new ModelPriceVersionRow();
        version.setId("price-version");
        var prices = new ModelPriceBookMapper().create(version, config);
        for (var instant : List.of("2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z")) {
            assertThat(calculator.calculate(prices, Instant.parse(instant), null, null, simple(100, 0), true).totalAmount())
                .isEqualByComparingTo("0.0001");
        }
        assertThat(calculator.calculate(prices, Instant.parse("2026-11-01T07:00:00Z"), null, null, simple(100, 0), true).totalAmount())
            .isEqualByComparingTo("0.0002");
    }

    private ModelTokenUsage simple(long input, long output) throws Exception {
        return usage("openai", "{\"prompt_tokens\":" + input + ",\"completion_tokens\":" + output
            + ",\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}}");
    }

    private ModelTokenUsage usage(String protocol, String json) throws Exception {
        return new ModelUsageNormalizer().normalize(protocol, "standard", new ObjectMapper().readTree(json), Set.of());
    }

    private ModelPriceBook book(Rule... rules) {
        var config = validation.normalize(new ModelPriceConfiguration("USD", "UTC", LocalDate.parse("2026-12-31"),
            null, null, null, List.of(rules)));
        validation.requirePublishable(config);
        var version = new ModelPriceVersionRow();
        version.setId("price-version");
        version.setEnterpriseId("enterprise");
        return new ModelPriceBookMapper().create(version, config);
    }

    private Rule base(String input, String output, String read, String write) {
        return new Rule("基础", 0, true, 0, null, "standard", List.of(), List.of(), List.of(
            rate("input_uncached", input), rate("output", output), rate("cache_read", read), rate("cache_write", write)));
    }

    private Rate rate(String meter, String price) {
        return new Rate(meter, 0, new BigDecimal(price));
    }
}
