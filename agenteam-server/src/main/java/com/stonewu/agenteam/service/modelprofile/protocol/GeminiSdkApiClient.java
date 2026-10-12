package com.stonewu.agenteam.service.modelprofile.protocol;

import com.google.genai.ApiClient;
import com.google.genai.ApiResponse;
import com.google.genai.errors.ApiException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 保留 Google 官方的地址、认证和请求转换，只将网络交给平台管理。
 */
public final class GeminiSdkApiClient extends ApiClient {
    private final ModelHttpSession session;
    private final ModelUsageAccumulator usage;
    private final ObjectMapper json;

    public GeminiSdkApiClient(String apiKey, String baseUrl, ModelHttpSession session) {
        this(apiKey, baseUrl, session, null, null);
    }

    public GeminiSdkApiClient(String apiKey, String baseUrl, ModelHttpSession session, ModelUsageAccumulator usage, ObjectMapper json) {
        super(Optional.of(apiKey == null ? "" : apiKey), Optional.of(HttpOptions.builder()
            .baseUrl(baseUrl).apiVersion("v1beta").retryOptions(HttpRetryOptions.builder().attempts(1).build())
            .build()), Optional.empty());
        this.session = session;
        this.usage = usage;
        this.json = json;
    }

    @Override
    public ApiResponse request(String method, String path, String body, Optional<HttpOptions> options) {
        try {
            return new SdkResponse(session.execute(buildRequest(method, path, body, options)));
        } catch (IOException failure) {
            throw new UncheckedIOException("模型服务请求失败。", failure);
        }
    }

    @Override
    public ApiResponse request(String method, String path, byte[] body, Optional<HttpOptions> options) {
        try {
            return new SdkResponse(session.execute(buildRequest(method, path, body, options)));
        } catch (IOException failure) {
            throw new UncheckedIOException("模型服务请求失败。", failure);
        }
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(String method, String path, String body,
                                                       Optional<HttpOptions> options) {
        return session.executeAsync(buildRequest(method, path, body, options), SdkResponse::new);
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(String method, String path, byte[] body,
                                                       Optional<HttpOptions> options) {
        return session.executeAsync(buildRequest(method, path, body, options), SdkResponse::new);
    }

    @Override
    public void close() {
        session.close();
        super.close();
    }

    /** 官方响应包装类不可见，使用公开接口并继续复用官方错误解析。 */
    private final class SdkResponse extends ApiResponse {
        private final Response response;

        private SdkResponse(Response response) {
            this.response = response;
        }

        @Override
        public ResponseBody getBody() {
            ApiException.throwFromResponse(response);
            return usage == null || !response.request().url().encodedPath().endsWith(":streamGenerateContent")
                ? response.body() : new GeminiUsageResponseBody(response.body(), usage, json);
        }

        @Override
        public Headers getHeaders() {
            return response.headers();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
