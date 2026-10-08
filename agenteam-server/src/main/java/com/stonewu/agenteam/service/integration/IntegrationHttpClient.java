package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 渠道请求不自动重试和跳转；日志只保留路径与接入编号，避免地址参数中的凭证泄漏。
 */
@Component
public class IntegrationHttpClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final ObjectMapper json;
    private final OkHttpClient client;

    @Autowired
    public IntegrationHttpClient(ObjectMapper json) {
        this(json, Duration.ofSeconds(10));
    }

    public IntegrationHttpClient(ObjectMapper json, Duration timeout) {
        this.json = json;
        this.client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    public ChannelHttpResponse get(String connection, URI address, Map<String, String> query, String bearer) {
        return execute(connection, new Request.Builder().url(withQuery(address, query).toString()), null, bearer,
            new ChannelRequestDiagnostics(query, null, bearer, json));
    }

    public ChannelHttpResponse post(String connection, URI address, Map<String, String> query,
                                    JsonNode body, String bearer) {
        return execute(connection, new Request.Builder().url(withQuery(address, query).toString()),
            RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")), bearer,
            new ChannelRequestDiagnostics(query, body, bearer, json));
    }

    public ChannelHttpResponse form(String connection, URI address, Map<String, String> fields) {
        var body = new FormBody.Builder();
        fields.forEach(body::add);
        return execute(connection, new Request.Builder().url(address.toString()), body.build(), null,
            new ChannelRequestDiagnostics(fields, null, null, json));
    }

    public static URI withQuery(URI address, Map<String, String> query) {
        var builder = HttpUrl.get(address).newBuilder();
        query.forEach(builder::addQueryParameter);
        return builder.build().uri();
    }

    private ChannelHttpResponse execute(String connection, Request.Builder builder, RequestBody body, String bearer,
                                        ChannelRequestDiagnostics diagnostics) {
        builder.header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.post(body);
        }
        Request request = builder.build();
        try (var response = client.newCall(request).execute()) {
            Long retryAfter = delay(response.header("x-ogw-ratelimit-reset"));
            if (retryAfter == null) {
                retryAfter = delay(response.header("Retry-After"));
            }
            byte[] bytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("平台响应超过允许大小");
            }
            JsonNode parsed;
            try {
                parsed = json.readTree(bytes);
            } catch (IOException malformed) {
                diagnostics.failure(connection, request, response.code(), trace(response.header("X-Tt-Logid")), malformed, true);
                parsed = null;
            }
            String traceId = trace(response.header("X-Tt-Logid"));
            diagnostics.response(connection, request, response.code(), parsed, traceId);
            return new ChannelHttpResponse(response.code(), parsed, retryAfter, traceId);
        } catch (IOException failure) {
            var safe = new ChannelProviderException("CHANNEL_REQUEST_UNCERTAIN", "平台请求未取得明确结果。", failure);
            diagnostics.failure(connection, request, null, null, failure, false);
            throw safe;
        }
    }

    private static Long delay(String value) {
        if (value == null || !value.matches("[0-9]{1,9}")) {
            return null;
        }
        return Long.parseLong(value);
    }

    private static String trace(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,191}") ? value : null;
    }
}
