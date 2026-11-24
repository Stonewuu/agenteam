package com.stonewu.agenteam.service.modelpricing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import jakarta.annotation.PreDestroy;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.Proxy;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 拉取以 USD 为基准的公开参考汇率；主源使用欧洲央行 Frankfurter，备源使用 ExchangeRate-API。 */
@Component
public class ExchangeRateClient {
    public static final String FRANKFURTER_URL = "https://api.frankfurter.dev/v1/latest?base=USD";
    public static final String FRANKFURTER_NAME = "Frankfurter (ECB)";
    public static final String OPEN_ER_URL = "https://open.er-api.com/v6/latest/USD";
    public static final String OPEN_ER_NAME = "ExchangeRate-API";
    private static final int MAX_BYTES = 256 * 1024;
    private final ObjectMapper json;
    private final OkHttpClient primaryClient;
    private final OkHttpClient fallbackClient;
    private final HttpUrl primaryUrl;
    private final HttpUrl fallbackUrl;

    @Autowired
    public ExchangeRateClient(OutboundAddressPolicy addresses) {
        this(
            buildClient(addresses, HttpUrl.get(FRANKFURTER_URL)),
            buildClient(addresses, HttpUrl.get(OPEN_ER_URL)),
            HttpUrl.get(FRANKFURTER_URL),
            HttpUrl.get(OPEN_ER_URL)
        );
    }

    ExchangeRateClient(OkHttpClient primaryClient, OkHttpClient fallbackClient, HttpUrl primaryUrl, HttpUrl fallbackUrl) {
        this.json = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(4096).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.primaryUrl = primaryUrl;
        this.fallbackUrl = fallbackUrl;
    }

    private static OkHttpClient buildClient(OutboundAddressPolicy addresses, HttpUrl target) {
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .dns(host -> addresses.resolve(target, Duration.ofSeconds(4)))
            .connectionPool(new ConnectionPool(1, 30, TimeUnit.SECONDS))
            .connectTimeout(Duration.ofSeconds(4)).readTimeout(Duration.ofSeconds(6)).callTimeout(Duration.ofSeconds(10))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    public RateTable fetchPrimary() {
        return fetch(primaryClient, primaryUrl, FRANKFURTER_NAME, FRANKFURTER_URL, false);
    }

    public RateTable fetchFallback() {
        return fetch(fallbackClient, fallbackUrl, OPEN_ER_NAME, OPEN_ER_URL, true);
    }

    private RateTable fetch(OkHttpClient client, HttpUrl targetUrl, String sourceName, String sourceUrl, boolean openErFormat) {
        var diagnostic = new ModelRequestDiagnostics(json, "exchange-rate:" + sourceName, null, List.of());
        var request = new Request.Builder().url(targetUrl).header("Accept", "application/json").build();
        diagnostic.request(request);
        try (var original = client.newCall(request).execute(); var response = diagnostic.response(original)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.fetch.1", response.code()));
            }
            if (response.body().contentLength() > MAX_BYTES) {
                throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.fetch.2"));
            }
            try (var input = new LimitedInputStream(response.body().byteStream())) {
                JsonNode root = json.readTree(input);
                return parse(root, sourceName, sourceUrl, openErFormat);
            }
        } catch (IOException | RuntimeException failure) {
            throw diagnostic.failure(failure);
        }
    }

    private RateTable parse(JsonNode root, String sourceName, String sourceUrl, boolean openErFormat) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.parse.1"));
        }
        if (openErFormat && !"success".equalsIgnoreCase(root.path("result").asText())) {
            throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.parse.1"));
        }
        String base = openErFormat ? root.path("base_code").asText() : root.path("base").asText();
        if (!"USD".equals(base)) {
            throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.parse.1"));
        }
        JsonNode ratesNode = root.get("rates");
        if (ratesNode == null || !ratesNode.isObject()) {
            throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.parse.1"));
        }
        var rates = new LinkedHashMap<String, BigDecimal>();
        rates.put("USD", BigDecimal.ONE);
        var fields = ratesNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String code = field.getKey().toUpperCase(Locale.ROOT);
            JsonNode value = field.getValue();
            if (code.matches("[A-Z]{3}") && value != null && value.isNumber()) {
                BigDecimal rate = value.decimalValue().stripTrailingZeros();
                if (rate.signum() > 0 && rate.compareTo(BigDecimal.valueOf(1000000)) <= 0) {
                    rates.put(code, rate);
                }
            }
        }
        if (rates.size() <= 1) {
            throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.parse.1"));
        }
        String effectiveDate = extractEffectiveDate(root, openErFormat);
        return new RateTable(Collections.unmodifiableMap(rates), effectiveDate, sourceName, sourceUrl);
    }

    private String extractEffectiveDate(JsonNode root, boolean openErFormat) {
        if (!openErFormat) {
            String date = root.path("date").asText("").trim();
            if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return date;
            }
            return "";
        }
        String utc = root.path("time_last_update_utc").asText("").trim();
        if (!utc.isEmpty()) {
            try {
                return ZonedDateTime.parse(utc, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString();
            } catch (DateTimeParseException ignored) {
                return "";
            }
        }
        return "";
    }

    @PreDestroy
    public void close() {
        primaryClient.connectionPool().evictAll();
        primaryClient.dispatcher().executorService().shutdown();
        if (fallbackClient != primaryClient) {
            fallbackClient.connectionPool().evictAll();
            fallbackClient.dispatcher().executorService().shutdown();
        }
    }

    public record RateTable(Map<String, BigDecimal> usdRates, String effectiveDate, String sourceName, String sourceUrl) {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining = MAX_BYTES;

        private LimitedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result >= 0 && --remaining < 0) {
                throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.fetch.2"));
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int result = in.read(buffer, offset, length);
            if (result > 0 && (remaining -= result) < 0) {
                throw new IOException(Messages.text("error.modelpricing.exchangeRateClient.fetch.2"));
            }
            return result;
        }
    }
}
