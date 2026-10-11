package com.stonewu.agenteam.service.modelpricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevCatalogParser;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevPriceMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.service.http.ApiException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ModelsDevCatalogTest {
    private static final Logger LOG = LoggerFactory.getLogger(ModelsDevCatalogTest.class);
    private static final String COST = "{\"input\":0.15,\"output\":0.6,\"cache_read\":0.003}";
    private final ObjectMapper json = new ObjectMapper();
    private final ModelsDevPriceMapper mapping = new ModelsDevPriceMapper();
    private final ModelsDevCatalogParser parser = new ModelsDevCatalogParser(mapping);

    @Test
    void importsExactNewContextBoundariesAndNeverUsesTheLegacyApproximation() throws Exception {
        var prices = mapping.map(json.readTree("""
            {"input":2.5,"output":15,"cache_read":0.25,
             "tiers":[{"input":5,"output":22.5,"cache_read":0.5,"tier":{"type":"context","size":272000}},
                      {"input":8,"output":30,"tier":{"type":"context","size":400000}}],
             "context_over_200k":{"input":999,"output":999}}
            """));
        assertThat(prices.rules()).hasSize(3);
        assertThat(prices.rules().get(1).contextMinTokens()).isEqualTo(272001);
        assertThat(prices.rules().get(1).contextMaxTokens()).isEqualTo(400001);
        assertThat(prices.rules().get(2).contextMinTokens()).isEqualTo(400001);
        assertThat(prices.rules().get(1).rates().getFirst().pricePerMillion()).isEqualTo("5");
        var legacy = mapping.map(json.readTree("{\"input\":1,\"output\":2,\"context_over_200k\":{\"input\":2,\"output\":4}}"));
        assertThat(legacy.rules().get(1).contextMinTokens()).isEqualTo(200001);
    }

    @Test
    void preservesZeroAndExactDecimalsWithoutInventingCacheOrExpiryPrices() throws Exception {
        var entry = read("""
            {"deepseek":{"models":{"deepseek-flash":{"name":"DeepSeek Flash","modalities":{"output":["text"]},
              "cost":{"input":0.000000000001,"output":0,"cache_write":1.25,"input_audio":1}}},
              "api":"https://api.deepseek.com","name":"DeepSeek"}}
            """).getFirst();
        assertThat(entry.rules().getFirst().rates()).extracting(rate -> rate.pricePerMillion()).containsExactly("0.000000000001", "0");
        assertThat(entry.notices()).hasSize(2);
        assertThat(entry.providerName()).isEqualTo("DeepSeek");
        assertThat(entry.api()).isEqualTo("https://api.deepseek.com");
        new ModelPriceValidation().requirePublishable(new ModelPriceValidation().normalize(
            new ModelPriceConfiguration("USD", "UTC", null,
                ModelsDevCatalogClient.SOURCE, Instant.now(), null, entry.rules())));
    }

    @Test
    void excludesInvalidOrUnsupportedPricesInsteadOfSilentlyChangingThem() throws Exception {
        for (String cost : List.of("{\"input\":-1,\"output\":2}", "{\"input\":1}",
            "{\"input\":1,\"output\":2,\"reasoning\":4}", "{\"input\":1000001,\"output\":1}",
            "{\"input\":1,\"output\":1,\"cache_read\":null}", "{\"input\":1e-13,\"output\":1}",
            "{\"input\":1,\"output\":1,\"tiers\":[{\"input\":2,\"output\":3,\"tier\":{\"type\":\"time\",\"size\":2}}]}")) {
            assertThat(mapping.map(json.readTree(cost))).as(cost).isNull();
        }
        assertThat(mapping.map(json.readTree("{\"input\":1,\"output\":2,\"reasoning\":2}"))).isNotNull();
    }

    @Test
    void rejectsAmbiguousTiersAndMalformedOrExcessiveObjects() throws Exception {
        var duplicate = "{\"input\":1,\"output\":2,\"tiers\":[{\"input\":2,\"output\":3,\"tier\":{\"size\":2}},{\"input\":3,\"output\":4,\"tier\":{\"size\":2}}]}";
        assertThat(mapping.map(json.readTree(duplicate))).isNull();
        for (String body : List.of("{}", "[]", "{\"provider\":{\"models\":{", "{\"provider\":{\"models\":[]}}")) {
            assertThatThrownBy(() -> read(body)).isInstanceOf(IOException.class);
        }
        String hugeName = "x".repeat(20000);
        assertThatThrownBy(() -> read(body(COST).replace("DeepSeek Flash", hugeName))).isInstanceOf(IOException.class);
    }

    @Test
    void matchesProviderAddressesAndIdsWithoutTreatingRelaysAsTheOriginalProvider() throws Exception {
        var entry = read(body(COST)).getFirst();
        assertThat(ModelsDevPriceSearch.matches(entry, "https://api.deepseek.com/v1/", "deepseek-flash", "openai")).isTrue();
        assertThat(ModelsDevPriceSearch.matches(entry, "https://relay.example/v1", "deepseek-flash", "openai")).isFalse();
        assertThat(ModelsDevPriceSearch.matches(entry, "https://api.deepseek.com.attacker.example", "deepseek-flash", "openai")).isFalse();
        assertThat(ModelsDevPriceSearch.matches(entry, "https://api.deepseek.com/private", "deepseek-flash", "openai")).isFalse();
        assertThat(ModelsDevPriceSearch.matches(entry, "https://api.deepseek.com", "custom-alias", "openai")).isFalse();
    }

    @Test
    void searchesNamesIdsProvidersAndMinorMisspellings() throws Exception {
        var entry = read(body(COST)).getFirst();
        for (String query : List.of("DEEPSEEK", "deepseekflash", "deepsek flash", "flas deepseek", "DeepSeek deepseek-flash")) {
            assertThat(ModelsDevPriceSearch.score(entry, query)).as(query).isPositive();
        }
        assertThat(ModelsDevPriceSearch.score(entry, "unrelated-model")).isZero();
    }

    @Test
    void coalescesColdConcurrentLoadsAndUsesOneSharedSnapshot() throws Exception {
        var client = mock(ModelsDevCatalogClient.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entries = read(body(COST));
        when(client.fetch(anyString())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return entries;
        });
        var cache = new ModelsDevCatalogCache(client, Clock.systemUTC());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> cache.load("model-a"));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> cache.load("model-b"));
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).isSameAs(second.get(3, TimeUnit.SECONDS));
            assertThat(cache.load("model-c").entries()).isSameAs(entries);
        }
        verify(client, times(1)).fetch(anyString());
    }

    @Test
    void retainsLastGoodPricesWithExplicitStalenessAndBoundsRetries() throws Exception {
        var client = mock(ModelsDevCatalogClient.class);
        var clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-12T01:00:00Z");
        when(clock.instant()).thenReturn(start);
        when(client.fetch(anyString())).thenReturn(read(body(COST))).thenThrow(new IllegalStateException("已由客户端脱敏记录"));
        var cache = new ModelsDevCatalogCache(client, clock);
        cache.load("first");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(13)));
        var stale = cache.load("second");
        assertThat(stale.stale()).isTrue();
        assertThat(stale.fetchedAt()).isEqualTo(start);
        cache.load("third");
        verify(client, times(2)).fetch(anyString());
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(8)));
        assertThatThrownBy(() -> cache.load("fourth")).isInstanceOf(ApiException.class);
    }

    @Test
    void logsSafeHttpAndBusinessErrorsWithoutSendingCredentialsOrFollowingRedirects(CapturedOutput output) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(429);
        var followed = new AtomicInteger();
        server.createContext("/api.json", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestURI().getRawQuery()).isNull();
            exchange.getResponseHeaders().add("x-request-id", "catalog-trace");
            exchange.getResponseHeaders().add("Location", "/other");
            byte[] data = "{\"error\":{\"code\":\"RATE_LIMIT\",\"message\":\"请求限流 token=private-secret\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), data.length);
            try (var body = exchange.getResponseBody()) {
                body.write(data);
            }
        });
        server.createContext("/other", exchange -> {
            followed.incrementAndGet();
            exchange.close();
        });
        server.start();
        var client = new ModelsDevCatalogClient(parser, json, new OkHttpClient.Builder().followRedirects(false).build(),
            HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/api.json"));
        try {
            for (int code : List.of(429, 200, 302)) {
                status.set(code);
                assertThatThrownBy(() -> client.fetch("business-model")).isInstanceOf(RuntimeException.class);
            }
            assertThat(followed.get()).isZero();
            assertThat(output.getAll()).contains("RATE_LIMIT", "请求限流", "catalog-trace", "business-model", "/api.json", "GET", "ModelsDevCatalogClient")
                .doesNotContain("private-secret");
        } finally {
            client.close();
            server.stop(0);
        }
    }

    private List<ModelPriceCatalogEntry> read(String value) throws IOException {
        return parser.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void boundsDeclaredSizeStreamingSizeAndReadTime(CapturedOutput output) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        var release = new CountDownLatch(1);
        server.createContext("/api.json", exchange -> {
            try (exchange) {
                if (mode.get() == 0) {
                    exchange.sendResponseHeaders(200, 16L * 1024 * 1024 + 1);
                } else if (mode.get() == 1) {
                    exchange.sendResponseHeaders(200, 0);
                    // 连续空白本身不会创建数据树，客户端仍须按已读取字节拒绝超大响应。
                    byte[] spaces = " ".repeat(8192).getBytes(StandardCharsets.UTF_8);
                    for (int index = 0; index <= 2048; index++) {
                        exchange.getResponseBody().write(spaces);
                    }
                } else {
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        LOG.warn("目录超时测试的等待被中断，模型=bounded-model", interrupted);
                    }
                }
            } catch (IOException expectedClose) {
                // 超限或超时后客户端主动关闭，测试服务无需继续发送。
                LOG.info("目录容量测试的客户端已关闭，模型=bounded-model", expectedClose);
                assertThat(expectedClose).hasStackTraceContaining("IOException");
            }
        });
        server.start();
        var client = new ModelsDevCatalogClient(parser, json,
            new OkHttpClient.Builder().callTimeout(Duration.ofMillis(600)).retryOnConnectionFailure(false).build(),
            HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/api.json"));
        try {
            for (int current = 0; current < 3; current++) {
                mode.set(current);
                assertThatThrownBy(() -> client.fetch("bounded-model")).isInstanceOf(RuntimeException.class);
            }
            assertThat(output.getAll()).contains("价格目录超过允许大小", "bounded-model", "/api.json");
        } finally {
            release.countDown();
            client.close();
            server.stop(0);
        }
    }

    private String body(String cost) {
        return "{\"deepseek\":{\"name\":\"DeepSeek\",\"api\":\"https://api.deepseek.com\",\"models\":{\"deepseek-flash\":{"
            + "\"name\":\"DeepSeek Flash\",\"modalities\":{\"output\":[\"text\"]},\"cost\":" + cost + "}}}}";
    }
}
