package com.stonewu.agenteam.service.modelprofile.protocol;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/**
 * 使用 OpenAI 公开传输接口，认证、流式解码和响应类型仍由官方开发库负责。
 */
public final class OpenAISdkHttpClient implements HttpClient {
    private final ModelHttpSession session;

    public OpenAISdkHttpClient(ModelHttpSession session) {
        this.session = session;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        try {
            return new SdkResponse(session.execute(request(request)));
        } catch (IOException failure) {
            throw new UncheckedIOException("模型服务请求失败。", failure);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        return session.executeAsync(request(request), SdkResponse::new);
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
        session.close();
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
