package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.mapper.modelprofile.RemoteModelMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;

/**
 * 只访问已保存提供方的模型列表，密钥不随跳转发送，也不返回远程错误正文。
 */
@Component
public class RemoteModelClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final OutboundAddressPolicy addresses;
    private final RemoteModelMapper mapper;
    private final OkHttpClient client;
    private final ObjectMapper json;

    @Autowired
    public RemoteModelClient(OutboundAddressPolicy addresses, RemoteModelMapper mapper, ObjectMapper json) {
        this(addresses, mapper, new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20)).build(), json);
    }

    RemoteModelClient(OutboundAddressPolicy addresses, RemoteModelMapper mapper, OkHttpClient client) {
        this(addresses, mapper, client, new ObjectMapper());
    }

    RemoteModelClient(OutboundAddressPolicy addresses, RemoteModelMapper mapper, OkHttpClient client, ObjectMapper json) {
        this.addresses = addresses;
        this.mapper = mapper;
        this.client = client;
        this.json = json;
    }

    public List<RemoteModelView> list(ModelProviderRecord provider) {
        String base = provider.baseUrl().replaceAll("/+$", "");
        String route = switch (provider.protocol()) {
            case "anthropic" -> "/v1/models";
            case "gemini" -> "/v1beta/models";
            default -> "/models";
        };
        HttpUrl url = HttpUrl.get(base + route);
        boolean local = Set.of("127.0.0.1", "localhost", "::1").contains(url.host());
        // 本机模型沿用配置表单允许的地址，其他地址复用现有外部网络限制。
        var connection = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).dns(host -> local
                ? addresses.resolveHost(host, Duration.ofSeconds(5), true)
                : addresses.resolve(url, Duration.ofSeconds(5))).build();
        var diagnostic = new ModelRequestDiagnostics(json, provider.id(), provider.apiKey(), List.of());
        var results = new LinkedHashMap<String, RemoteModelView>();
        var tokens = new HashSet<String>();
        String cursor = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int remainingBytes = MAX_RESPONSE_BYTES;
        try {
            for (int page = 0; page < 10; page++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new InterruptedIOException("模型目录分页超过读取时间限制。");
                }
                var target = url.newBuilder();
                if (provider.protocol().equals("anthropic")) {
                    target.addQueryParameter("limit", "100");
                    if (cursor != null) {
                        target.addQueryParameter("after_id", cursor);
                    }
                } else if (provider.protocol().equals("gemini")) {
                    target.addQueryParameter("pageSize", "100");
                    if (cursor != null) {
                        target.addQueryParameter("pageToken", cursor);
                    }
                }
                var request = request(provider, target.build());
                diagnostic.request(request);
                try (var original = connection.newBuilder().callTimeout(Duration.ofNanos(remaining)).build().newCall(request).execute();
                     var response = diagnostic.response(original)) {
                    if (!response.isSuccessful()) {
                        throw statusError(response.code());
                    }
                    if (response.body() == null) {
                        throw statusError(502);
                    }
                    if (response.body().contentLength() > remainingBytes) {
                        throw tooLarge();
                    }
                    byte[] body = response.body().byteStream().readNBytes(remainingBytes + 1);
                    if (body.length > remainingBytes) {
                        throw tooLarge();
                    }
                    remainingBytes -= body.length;
                    var root = mapper.parse(body);
                    if (root.hasNonNull("error")) {
                        diagnostic.captureError(root.get("error"));
                        throw statusError(502);
                    }
                    var result = mapper.readPage(root, provider.protocol());
                    result.items().forEach(value -> results.putIfAbsent(value.id(), value));
                    if (results.size() > 1000) {
                        throw tooLarge();
                    }
                    if (!result.hasMore()) {
                        return results.values().stream().sorted(Comparator.comparing(RemoteModelView::id)).toList();
                    }
                    if (!tokens.add(result.next())) {
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_INVALID", "提供方重复返回同一页，请手动填写模型标识。");
                    }
                    cursor = result.next();
                }
            }
            throw tooLarge();
        } catch (InterruptedIOException timeout) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_LIST_TIMEOUT",
                "获取模型列表超时，请重试或手动填写模型标识。", diagnostic.failure(timeout));
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_UNAVAILABLE",
                "无法连接模型提供方，请检查服务地址后重试。", diagnostic.failure(failure));
        } catch (ApiException failure) {
            throw new ApiException(HttpStatus.valueOf(failure.getStatusCode().value()), failure.code(), failure.getReason(), diagnostic.failure(failure));
        }
    }

    private Request request(ModelProviderRecord provider, HttpUrl url) {
        var builder = new Request.Builder().url(url).header("Accept", "application/json").get();
        if (provider.protocol().equals("anthropic")) {
            builder.header("anthropic-version", "2023-06-01");
        }
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            switch (provider.protocol()) {
                case "anthropic" -> builder.header("x-api-key", provider.apiKey());
                case "gemini" -> builder.header("x-goog-api-key", provider.apiKey());
                default -> builder.header("Authorization", "Bearer " + provider.apiKey());
            }
        }
        return builder.build();
    }

    private ApiException statusError(int status) {
        String message = switch (status) {
            case 401, 403 -> "提供方拒绝访问，请检查连接中保存的访问密钥及其模型读取权限。";
            case 404, 405 -> "此服务未提供模型列表，请检查服务地址或手动填写模型标识。";
            case 429 -> "获取模型列表过于频繁，请稍后重试。";
            case 301, 302, 303, 307, 308 -> "模型服务地址发生跳转，请在连接配置中填写最终服务地址。";
            default -> "提供方暂时无法返回模型列表，请稍后重试。";
        };
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_UNAVAILABLE", message);
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_TOO_LARGE",
            "提供方返回的模型列表过大，请手动填写模型标识。");
    }
}
