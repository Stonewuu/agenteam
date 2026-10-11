package com.stonewu.agenteam.service.modelprofile.protocol;

import com.anthropic.backends.Backend;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/**
 * 使用 Anthropic 公开传输接口，平台统一控制取消和单次发送。
 */
public final class AnthropicSdkHttpClient implements HttpClient {
    private final ModelHttpSession session;
    private final Backend backend;

    public AnthropicSdkHttpClient(ModelHttpSession session, Backend backend) {
        this.session = session;
        this.backend = backend;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        try {
            var response = new SdkResponse(session.execute(request(authorize(request))));
            try {
                return backend.prepareResponse(response);
            } catch (RuntimeException failure) {
                response.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("模型服务请求失败。", failure);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        return session.executeAsync(request(authorize(request)),
            response -> backend.prepareResponse(new SdkResponse(response)));
    }

    private HttpRequest authorize(HttpRequest request) {
        return backend.authorizeRequest(backend.prepareRequest(request));
    }

    private Request request(HttpRequest source) {
        var result = new Request.Builder().url(source.url()).method(source.method().name(), body(source.body()));
        for (String name : source.headers().names()) {
            for (String value : source.headers().values(name)) {
                result.addHeader(name, value);
            }
        }
        return result.build();
    }

    private RequestBody body(HttpRequestBody source) {
        if (source == null) {
            return null;
        }
        return new ModelSdkRequestBody(source.contentType(), source.contentLength(), source::writeTo);
    }

    @Override
    public void close() {
        try {
            backend.close();
        } finally {
            session.close();
        }
    }

    private record SdkResponse(Response response) implements HttpResponse {
        @Override
        public int statusCode() {
            return response.code();
        }

        @Override
        public Headers headers() {
            return Headers.builder().putAll(response.headers().toMultimap()).build();
        }

        @Override
        public InputStream body() {
            return response.body() == null ? InputStream.nullInputStream() : response.body().byteStream();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
