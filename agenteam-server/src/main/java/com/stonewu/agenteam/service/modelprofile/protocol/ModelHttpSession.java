package com.stonewu.agenteam.service.modelprofile.protocol;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 每次模型请求独占可取消的连接作用域，开发库不能在同一记录下悄悄重发。
 */
public final class ModelHttpSession implements AutoCloseable {
    private final HttpUrl origin;
    private final OkHttpClient client;
    private final Runnable beforeDispatch;
    private final ModelRequestDiagnostics diagnostics;
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Call> activeCall = new AtomicReference<>();

    public ModelHttpSession(String baseUrl, OutboundAddressPolicy addresses, Runnable beforeDispatch) {
        this(baseUrl, addresses, beforeDispatch, null);
    }

    public ModelHttpSession(String baseUrl, OutboundAddressPolicy addresses, Runnable beforeDispatch,
                            ModelRequestDiagnostics diagnostics) {
        this.origin = HttpUrl.get(baseUrl);
        this.beforeDispatch = beforeDispatch;
        this.diagnostics = diagnostics;
        boolean local = Set.of("127.0.0.1", "localhost", "::1").contains(origin.host());
        if (!local) {
            addresses.validateUrl(baseUrl);
        }
        this.client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .protocols(origin.isHttps() ? List.of(Protocol.HTTP_2, Protocol.HTTP_1_1) : List.of(Protocol.HTTP_1_1))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ZERO).writeTimeout(Duration.ZERO)
            .addInterceptor(this::intercept)
            .dns(host -> {
                if (!host.equals(origin.host())) {
                    throw new UnknownHostException("模型请求不能访问其他主机。");
                }
                return local ? addresses.resolveHost(host, Duration.ofSeconds(5), true)
                    : addresses.resolve(origin, Duration.ofSeconds(5));
            }).build();
    }

    public Response execute(Request request) throws IOException {
        return prepare(request).execute();
    }

    public <T> CompletableFuture<T> executeAsync(Request request, Function<Response, T> mapper) {
        Call call = prepare(request);
        var result = new CompletableFuture<T>();
        result.whenComplete((response, failure) -> {
            if (result.isCancelled()) {
                call.cancel();
            }
        });
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                try {
                    if (!result.complete(mapper.apply(response))) {
                        response.close();
                    }
                } catch (RuntimeException failure) {
                    response.close();
                    result.completeExceptionally(failure);
                }
            }
        });
        return result;
    }

    private Call prepare(Request request) {
        if (closed.get() || cancelled.get()) {
            throw new CancellationException("模型请求已经取消。");
        }
        Call call = client.newCall(request);
        activeCall.set(call);
        if (cancelled.get()) {
            call.cancel();
        }
        return call;
    }

    /** 官方传输可以复用客户端，但所有实际发送仍须经过本类的请求登记和地址检查。 */
    public OkHttpClient client() {
        return client;
    }

    private Response intercept(Interceptor.Chain chain) throws IOException {
        if (closed.get() || cancelled.get()) {
            throw new InterruptedIOException("模型请求已经取消。");
        }
        if (!OutboundAddressPolicy.sameOrigin(origin, chain.request().url())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_REQUEST_ORIGIN_CHANGED",
                "模型请求地址与已配置的服务不一致。");
        }
        if (!dispatched.compareAndSet(false, true)) {
            throw new IllegalStateException("模型开发库尝试在同一次请求中重复发送，已停止未记录的重试。");
        }
        if (diagnostics != null) {
            diagnostics.request(chain.request());
        }
        beforeDispatch.run();
        activeCall.set(chain.call());
        if (closed.get() || cancelled.get()) {
            throw new InterruptedIOException("模型请求已经取消。");
        }
        var response = chain.proceed(chain.request());
        if (diagnostics != null) {
            response = diagnostics.response(response);
        }
        return response.body() == null ? response
            : response.newBuilder().body(new ModelResponseBody(response.body(), this, diagnostics,
                chain.request().url().encodedPath().endsWith(":streamGenerateContent"))).build();
    }

    public void cancel() {
        cancelled.set(true);
        Call call = activeCall.get();
        if (call != null) {
            call.cancel();
        }
        client.dispatcher().cancelAll();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancel();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
