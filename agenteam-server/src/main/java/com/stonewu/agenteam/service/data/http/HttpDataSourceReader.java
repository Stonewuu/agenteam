package com.stonewu.agenteam.service.data.http;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import okhttp3.HttpUrl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 只读取配置中的 HTTPS 地址，一次查询只发起一次读取，不追随响应中的下一页链接。
 */
@Service
public class HttpDataSourceReader {
    private static final int MAX_BYTES = 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BYTES).maxNumberLength(1000)
                    .build()).build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RestrictedHttpClient http;
    private final HttpCredentialService credentials;

    public HttpDataSourceReader(RestrictedHttpClient http, HttpCredentialService credentials) {
        this.http = http;
        this.credentials = credentials;
    }

    public JsonNode read(String enterprise, JsonNode config, List<DataQueryPlan.Condition> conditions, long deadline) {
        try (var control = new ToolCallControl(() -> {
        })) {
            return read(enterprise, config, conditions, deadline, control);
        }
    }

    public JsonNode read(String enterprise, JsonNode config, List<DataQueryPlan.Condition> conditions, long deadline,
                         ToolCallControl control) {
        String endpoint = config.at("/connection/endpoint").asText();
        HttpUrl url = HttpUrl.parse(endpoint);
        if (url == null || !url.isHttps()) {
            throw ApiException.invalidField("connection.endpoint", Messages.text("error.data.httpDataSourceReader.read.1"));
        }
        var parameters = new HashSet<String>();
        config.at("/connection/queryParameters").forEach(value -> parameters.add(value.asText()));
        var address = url.newBuilder();
        var sent = new HashSet<String>();
        for (var condition : conditions) {
            String name = condition.field().name();
            if (!parameters.contains(name)) {
                continue;
            }
            if (!condition.operator().equals("eq") || condition.value().isContainerNode()) {
                throw ApiException.invalidField("filters", Messages.text("error.data.httpDataSourceReader.read.2"));
            }
            if (!sent.add(name)) {
                throw ApiException.invalidField("filters", Messages.text("error.data.httpDataSourceReader.read.3"));
            }
            address.setQueryParameter(name, condition.value().asText());
        }
        String credentialId = config.path("credentialId").isTextual() ? config.path("credentialId").asText() : null;
        var connection = credentials.resolve(enterprise, credentialId);
        try (var scope = control.track(
            http.open(endpoint, connection.headers(), Duration.ofNanos(remaining(deadline))));
             var exchange = scope.execute("GET", address.build().toString(), null, Map.of("Accept", "application/json"),
                 control::beforeSend)) {
            var response = exchange.response();
            if (!response.isSuccessful() || response.body() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_HTTP_RESPONSE_FAILED",
                    Messages.text("error.data.httpDataSourceReader.read.4"));
            }
            var body = response.body();
            var media = body.contentType();
            if (media == null || !media.type().equals("application") || !(media.subtype()
                .equals("json") || media.subtype().endsWith("+json"))) {
                throw invalid();
            }
            if (body.contentLength() > MAX_BYTES) {
                throw tooLarge();
            }
            byte[] bytes = body.byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw tooLarge();
            }
            remaining(deadline);
            JsonNode value = json.readTree(bytes);
            if (value == null || !(value.isArray() || value.isObject())) {
                throw invalid();
            }
            return value;
        } catch (ApiException failed) {
            throw failed;
        } catch (IOException failed) {
            remaining(deadline);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_HTTP_READ_FAILED",
                Messages.text("error.data.httpDataSourceReader.read.5"), failed);
        }
    }

    public static long remaining(long deadline) {
        long value = deadline - System.nanoTime();
        if (value <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATA_QUERY_TIMEOUT", Messages.text("error.data.httpDataSourceReader.remaining.1"));
        }
        return value;
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "DATA_HTTP_JSON_REQUIRED", Messages.text("error.data.httpDataSourceReader.invalid.1"));
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_HTTP_RESPONSE_TOO_LARGE",
            Messages.text("error.data.httpDataSourceReader.tooLarge.1"));
    }
}
