package com.stonewu.agenteam.service.network;

import com.stonewu.agenteam.service.http.ApiException;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个调用范围共用截止时间和取消信号；自动重试、代理及自动重定向均关闭。
 */
@Component
public class RestrictedHttpClient {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("外部网页连接期限").factory());
    private final OutboundAddressPolicy policy;
    private final OkHttpClient prototype;

    @Autowired
    public RestrictedHttpClient(OutboundAddressPolicy policy) {
        this(policy, new OkHttpClient());
    }

    public RestrictedHttpClient(OutboundAddressPolicy policy, OkHttpClient prototype) {
        this.policy = policy;
        this.prototype = prototype;
    }

    public Scope open(String endpoint, Map<String, String> credentials, Duration timeout) {
        return new Scope(policy.validateUrl(endpoint), credentials, timeout);
    }

    public final class Scope implements AutoCloseable {
        private final HttpUrl endpoint;
        private final Map<String, String> credentials;
        private final long deadline;
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        private final Set<Thread> resolving = ConcurrentHashMap.newKeySet();
        private final Dispatcher dispatcher = new Dispatcher();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread owner = Thread.currentThread();
        private final ScheduledFuture<?> expiration;

        private Scope(HttpUrl endpoint, Map<String, String> credentials, Duration timeout) {
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
                throw new IllegalArgumentException("连接超时必须在一百二十秒以内");
            }
            this.endpoint = endpoint;
            this.credentials = Map.copyOf(credentials);
            this.deadline = System.nanoTime() + timeout.toNanos();
            this.expiration = TIMER.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() >= deadline || owner.isInterrupted()) {
                    close();
                }
            }, 25, 25, TimeUnit.MILLISECONDS);
        }

        public long remainingMillis() {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (closed.get() || remaining <= 0) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "REMOTE_TIMEOUT", "连接已取消或超过允许时间。");
            }
            return remaining;
        }

        public Exchange execute(String method, String address, byte[] body,
                                Map<String, String> headers) throws IOException {
            return execute(method, address, body, headers, () -> {
            });
        }

        public Exchange execute(String method, String address, byte[] body, Map<String, String> headers,
                                Runnable beforeSend) throws IOException {
            HttpUrl url = policy.validateUrl(address);
            boolean forwardCredentials = OutboundAddressPolicy.sameOrigin(endpoint, url);
            for (int redirects = 0; ; redirects++) {
                var resolvingThread = Thread.currentThread();
                resolving.add(resolvingThread);
                List<InetAddress> addresses;
                try {
                    addresses = policy.resolve(url, Duration.ofMillis(Math.min(10_000, remainingMillis())));
                } finally {
                    resolving.remove(resolvingThread);
                }
                String host = url.host();
                long remaining = remainingMillis();
                var client = prototype.newBuilder().dispatcher(dispatcher)
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)).proxy(Proxy.NO_PROXY)
                    .protocols(List.of(Protocol.HTTP_1_1)).retryOnConnectionFailure(false)
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(Math.min(10_000, remaining), TimeUnit.MILLISECONDS)
                    .readTimeout(remaining, TimeUnit.MILLISECONDS).writeTimeout(remaining, TimeUnit.MILLISECONDS)
                    .callTimeout(remaining, TimeUnit.MILLISECONDS)
                    .addNetworkInterceptor(chain -> {
                        beforeSend.run();
                        return chain.proceed(chain.request());
                    })
                    .dns(requested -> {
                        if (!requested.equals(host)) {
                            throw new UnknownHostException("请求主机发生变化");
                        }
                        return addresses;
                    }).build();
                Request.Builder request = new Request.Builder().url(url)
                    .method(method, body == null ? null : RequestBody.create(body,
                        MediaType.get("application/json; charset=utf-8")));
                headers.forEach((key, value) -> {
                    if (!key.equalsIgnoreCase("Authorization") && !key.equalsIgnoreCase(
                        "Cookie") && !key.equalsIgnoreCase("X-API-Key")) {
                        request.header(key, value);
                    }
                });
                if (forwardCredentials) {
                    credentials.forEach(request::header);
                } else {
                    request.removeHeader("MCP-Session-Id");
                }
                Call call = client.newCall(request.build());
                calls.add(call);
                if (closed.get()) {
                    call.cancel();
                    calls.remove(call);
                    throw new IOException("连接已取消");
                }
                Response response;
                try {
                    response = call.execute();
                } catch (IOException | RuntimeException failure) {
                    calls.remove(call);
                    client.connectionPool().evictAll();
                    throw failure;
                }
                int status = response.code();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    return new Exchange(response, call, client);
                }
                String location = response.header("Location");
                response.close();
                calls.remove(call);
                client.connectionPool().evictAll();
                HttpUrl next = location == null ? null : url.resolve(location);
                if (redirects >= 3 || next == null || (url.isHttps() && !next.isHttps())) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_REDIRECT_DENIED",
                        "连接返回了不支持的跳转，请检查插件地址。");
                }
                // POST 只接受明确保留方法和正文的跳转，不能把外部写入悄悄改成其他请求。
                if (!method.equals("GET") && !method.equals("HEAD") && status != 307 && status != 308) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_REDIRECT_DENIED",
                        "连接跳转不能保留本次请求，请填写最终服务地址。");
                }
                forwardCredentials &= OutboundAddressPolicy.sameOrigin(url, next);
                url = policy.validateUrl(next.toString());
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            expiration.cancel(false);
            calls.forEach(Call::cancel);
            resolving.forEach(Thread::interrupt);
            dispatcher.cancelAll();
            dispatcher.executorService().shutdown();
        }

        public final class Exchange implements AutoCloseable {
            private final Response response;
            private final Call call;
            private final OkHttpClient client;

            private Exchange(Response response, Call call, OkHttpClient client) {
                this.response = response;
                this.call = call;
                this.client = client;
            }

            public Response response() {
                return response;
            }

            @Override
            public void close() {
                response.close();
                calls.remove(call);
                client.connectionPool().evictAll();
            }
        }
    }
}
