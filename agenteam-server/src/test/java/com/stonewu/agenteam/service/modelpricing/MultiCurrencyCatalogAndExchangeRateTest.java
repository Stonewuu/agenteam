package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.mapper.modelpricing.LlmAbacusCatalogParser;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevCatalogParser;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevPriceMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiCurrencyCatalogAndExchangeRateTest {
    private static final String LLM_ABACUS_SAMPLE = """
        {
          "vendors": [
            {"id": "alibaba", "name": "阿里通义", "currency": "CNY"},
            {"id": "deepseek", "name": "DeepSeek", "currency": "CNY"},
            {"id": "openai", "name": "OpenAI", "currency": "USD"}
          ],
          "models": [
            {
              "id": "gpt-5",
              "name": "GPT-5",
              "vendorId": "openai",
              "modality": ["text"],
              "priceCurrency": "USD",
              "inputPrice": 5,
              "outputPrice": 15
            },
            {
              "id": "qwen3-5-flash",
              "name": "Qwen3.5 Flash",
              "vendorId": "alibaba",
              "modality": ["text", "vision"],
              "priceCurrency": "CNY",
              "inputPrice": 0.2,
              "outputPrice": 2,
              "cachedInputPrice": null,
              "source": "https://help.aliyun.com/zh/model-studio/model-pricing",
              "tiers": [
                {"upTo": 128000, "inputPrice": 0.2, "outputPrice": 2, "cachedInputPrice": null},
                {"upTo": 256000, "inputPrice": 0.8, "outputPrice": 8, "cachedInputPrice": null},
                {"upTo": null, "inputPrice": 1.2, "outputPrice": 12, "cachedInputPrice": null}
              ]
            },
            {
              "id": "deepseek-v4-flash",
              "name": "DeepSeek V4 Flash",
              "vendorId": "deepseek",
              "modality": ["text"],
              "priceCurrency": "CNY",
              "inputPrice": 2,
              "outputPrice": 8,
              "cachedInputPrice": 0.04,
              "source": "https://api-docs.deepseek.com/quick_start/pricing",
              "timePricing": {
                "timezone": "Asia/Shanghai",
                "defaultBand": "peak",
                "bands": [
                  {
                    "id": "off_peak",
                    "label": "空闲时段",
                    "schedule": "00:00-09:00、12:00-14:00、18:00-24:00",
                    "inputPrice": 1,
                    "outputPrice": 4,
                    "cachedInputPrice": 0.02
                  },
                  {
                    "id": "peak",
                    "label": "高峰时段",
                    "schedule": "09:00-12:00、14:00-18:00",
                    "inputPrice": 2,
                    "outputPrice": 8,
                    "cachedInputPrice": 0.04
                  }
                ]
              }
            }
          ]
        }
        """;

    private static final String MODELS_DEV_SAMPLE = """
        {
          "deepseek": {
            "name": "DeepSeek",
            "api": "https://api.deepseek.com",
            "models": {
              "deepseek-v4-flash": {
                "name": "DeepSeek V4 Flash",
                "modalities": {"output": ["text"]},
                "cost": {"input": 0.15, "output": 0.6, "cache_read": 0.003}
              }
            }
          }
        }
        """;

    @Test
    void parsesLlmAbacusCnyTiersAndTimePricingIntoPublishableConfigurations() throws Exception {
        var parser = new LlmAbacusCatalogParser();
        var entries = parser.read(new ByteArrayInputStream(LLM_ABACUS_SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertThat(entries).hasSize(2);
        var validation = new ModelPriceValidation();

        var qwen = entries.stream().filter(entry -> entry.modelId().equals("qwen3-5-flash")).findFirst().orElseThrow();
        assertThat(qwen.currency()).isEqualTo("CNY");
        assertThat(qwen.providerId()).isEqualTo("llmabacus:alibaba");
        assertThat(qwen.providerName()).isEqualTo("阿里通义");
        assertThat(qwen.rules()).hasSize(3);
        assertThat(qwen.rules().get(1).contextMinTokens()).isEqualTo(128001);
        assertThat(qwen.rules().get(1).contextMaxTokens()).isEqualTo(256001);
        assertThat(qwen.rules().get(2).contextMinTokens()).isEqualTo(256001);
        assertThat(qwen.rules().get(2).contextMaxTokens()).isEqualTo(-1L);
        validation.requirePublishable(validation.normalize(new ModelPriceConfiguration(
            qwen.currency(), qwen.timezone(), null, qwen.sourceReference(), Instant.now(), null, qwen.rules())));

        var deepseek = entries.stream().filter(entry -> entry.modelId().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        assertThat(deepseek.currency()).isEqualTo("CNY");
        assertThat(deepseek.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(deepseek.rules()).hasSize(2);
        assertThat(deepseek.rules().get(1).times()).hasSize(21);
        validation.requirePublishable(validation.normalize(new ModelPriceConfiguration(
            deepseek.currency(), deepseek.timezone(), null, deepseek.sourceReference(), Instant.now(), null, deepseek.rules())));
    }

    @Test
    void mergesCatalogSourcesSupportsCurrencyFilteringAndDegradesGracefullyWhenOneSourceFails() throws Exception {
        var modelsDevEntries = new ModelsDevCatalogParser(new ModelsDevPriceMapper())
            .read(new ByteArrayInputStream(MODELS_DEV_SAMPLE.getBytes(StandardCharsets.UTF_8)));
        var llmAbacusEntries = new LlmAbacusCatalogParser()
            .read(new ByteArrayInputStream(LLM_ABACUS_SAMPLE.getBytes(StandardCharsets.UTF_8)));

        var prices = mock(ModelPriceService.class);
        doNothing().when(prices).authorize(any(), anyBoolean());
        var profileMapper = mock(ModelProfileMapper.class);
        var modelRow = mock(ModelProfileRecord.class);
        when(modelRow.baseUrl()).thenReturn("https://api.deepseek.com/v1");
        when(modelRow.modelName()).thenReturn("deepseek-v4-flash");
        when(modelRow.provider()).thenReturn("openai");
        when(profileMapper.find("ent-1", "model-1")).thenReturn(Optional.of(modelRow));

        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("ent-1");

        Instant now = Instant.parse("2026-11-25T05:00:00Z");
        var modelsDevCache = mock(ModelsDevCatalogCache.class);
        var llmAbacusCache = mock(LlmAbacusCatalogCache.class);
        when(modelsDevCache.load(anyString())).thenReturn(new ModelsDevCatalogCache.Snapshot(modelsDevEntries, now, false));
        when(llmAbacusCache.load(anyString())).thenReturn(new LlmAbacusCatalogCache.Snapshot(llmAbacusEntries, now, false));

        var service = new ModelPriceCatalogService(prices, profileMapper, modelsDevCache, llmAbacusCache);
        var all = service.search(actor, "model-1", "deepseek", "", "", 0, false);
        assertThat(all.currencies()).containsExactly("CNY", "USD");
        assertThat(all.items()).hasSize(2);
        assertThat(all.items()).extracting(item -> item.configuration().currency()).containsExactly("CNY", "USD");
        assertThat(all.providers()).extracting(ModelPriceCatalogViewProviderName -> ModelPriceCatalogViewProviderName.name())
            .contains("DeepSeek (CNY)", "DeepSeek (USD)", "阿里通义");

        var cnyOnly = service.search(actor, "model-1", "", "", "CNY", 0, false);
        assertThat(cnyOnly.items()).hasSize(2);
        assertThat(cnyOnly.items()).allMatch(item -> "CNY".equals(item.configuration().currency()));

        var usdOnly = service.search(actor, "model-1", "", "", "USD", 0, false);
        assertThat(usdOnly.items()).hasSize(1);
        assertThat(usdOnly.items().getFirst().sourceId()).isEqualTo("models_dev");

        // 单个来源故障时自动降级保留另一来源
        when(llmAbacusCache.load(anyString())).thenThrow(new IllegalStateException("cny source unavailable"));
        var degraded = service.search(actor, "model-1", "deepseek", "", "", 0, false);
        assertThat(degraded.stale()).isTrue();
        assertThat(degraded.currencies()).containsExactly("USD");
        assertThat(degraded.items()).hasSize(1);
    }

    @Test
    void quotesLiveRatesComputesCrossRatesAndFallsBackToSecondarySource() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var primaryStatus = new AtomicInteger(200);
        server.createContext("/frankfurter", exchange -> {
            byte[] body = "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-11-24\",\"rates\":{\"CNY\":7.2,\"EUR\":0.9}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(primaryStatus.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/open-er", exchange -> {
            byte[] body = """
                {"result":"success","base_code":"USD","time_last_update_utc":"Wed, 25 Nov 2026 00:02:31 +0000",
                 "rates":{"USD":1,"CNY":7.25,"TWD":32.5}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        var http = new OkHttpClient.Builder().followRedirects(false).build();
        int port = server.getAddress().getPort();
        var client = new ExchangeRateClient(http, http,
            HttpUrl.get("http://127.0.0.1:" + port + "/frankfurter"),
            HttpUrl.get("http://127.0.0.1:" + port + "/open-er"));
        try {
            var clock = Clock.fixed(Instant.parse("2026-11-25T05:00:00Z"), ZoneOffset.UTC);
            var cache = new ExchangeRateCache(client, clock);
            var prices = mock(ModelPriceService.class);
            doNothing().when(prices).authorize(any(), anyBoolean());
            var actor = mock(AuthContext.class);
            var service = new ExchangeRateService(prices, cache, clock);

            // 主源 USD -> CNY 与交叉计算 CNY -> EUR
            var usdToCny = service.quote(actor, "USD", "CNY");
            assertThat(usdToCny.rate()).isEqualTo("7.2");
            assertThat(usdToCny.effectiveDate()).isEqualTo("2026-11-24");
            assertThat(usdToCny.sourceName()).isEqualTo(ExchangeRateClient.FRANKFURTER_NAME);

            var cnyToEur = service.quote(actor, "CNY", "EUR");
            assertThat(cnyToEur.rate()).isEqualTo("0.125");

            // 主源不含 TWD 时自动切换到备选源
            var usdToTwd = service.quote(actor, "USD", "TWD");
            assertThat(usdToTwd.rate()).isEqualTo("32.5");
            assertThat(usdToTwd.effectiveDate()).isEqualTo("2026-11-25");
            assertThat(usdToTwd.sourceName()).isEqualTo(ExchangeRateClient.OPEN_ER_NAME);

            // 相同币种直接返回 1
            assertThat(service.quote(actor, "CNY", "CNY").rate()).isEqualTo("1");

            // 非法币种报错
            assertThatThrownBy(() -> service.quote(actor, "ZZZ", "CNY")).isInstanceOf(ApiException.class);
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
