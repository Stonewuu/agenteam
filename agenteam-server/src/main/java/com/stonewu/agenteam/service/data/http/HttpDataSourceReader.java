package com.stonewu.agenteam.service.data.http;

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
            throw ApiException.invalidField("connection.endpoint", "数据接口必须使用 HTTPS 加密地址。");
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
                throw ApiException.invalidField("filters", "传给接口的查询参数只接受单个相等条件。");
            }
            if (!sent.add(name)) {
                throw ApiException.invalidField("filters", "传给接口的同一参数不能重复设置。");
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
                    "数据接口没有返回成功结果，请检查连接与凭据。");
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
                "数据接口无法读取，或返回的 JSON 内容无效，请检查接口后重试。", failed);
        }
    }

    public static long remaining(long deadline) {
        long value = deadline - System.nanoTime();
        if (value <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATA_QUERY_TIMEOUT", "数据查询已取消或超过允许时间。");
        }
        return value;
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "DATA_HTTP_JSON_REQUIRED", "数据接口必须返回 JSON 对象或数组。");
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_HTTP_RESPONSE_TOO_LARGE",
            "数据接口返回内容超过一 MiB，请在接口端缩小单次返回范围。");
    }
}
