package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.HttpUrl;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 沿用 MCP 客户端的版本协商与请求关联，只替换不满足平台网络限制的传输层。
 */
public final class RestrictedMcpTransport implements McpClientTransport {
    private final RestrictedHttpClient.Scope network;
    private final String endpoint;
    private final boolean legacy;
    private final ObjectMapper json;
    private final ObjectMapper incoming;
    private final McpJsonMapper protocolJson = McpJsonMapper.getDefault();
    private final McpEventReader.Budget responseBudget;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<String> legacyEndpoint = new CompletableFuture<>();
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
    private volatile Consumer<Throwable> failureHandler = ignored -> {
    };
    private volatile String sessionId;
    private volatile String protocolVersion = "2025-11-25";
    private volatile Runnable beforeToolSend = () -> {
    };

    public RestrictedMcpTransport(RestrictedHttpClient http, ObjectMapper json, String endpoint, String transport,
                                  Map<String, String> credentials, Duration timeout, long responseBytes) {
        if (!transport.equals("streamable_http") && !transport.equals("legacy_sse")) {
            throw ApiException.invalidField("config.transport", "请选择支持的远程连接方式。");
        }
        this.endpoint = endpoint;
        this.legacy = transport.equals("legacy_sse");
        this.json = json;
        this.protocolVersion = legacy ? "2024-11-05" : "2025-11-25";
        this.network = http.open(endpoint, credentials, timeout);
        this.responseBudget = new McpEventReader.Budget(responseBytes);
        incoming = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                .maxStringLength((int) Math.min(responseBytes, 16 * 1024 * 1024))
                .maxNumberLength(1000).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public void beforeToolSend(Runnable action) {
        beforeToolSend = action;
    }

    @Override
    public List<String> protocolVersions() {
        return legacy ? List.of("2024-11-05") : List.of("2025-03-26", "2025-06-18", "2025-11-25");
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        failureHandler = handler;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.handler = handler;
        if (!legacy) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            Thread.ofVirtual().name("插件事件读取").start(this::readLegacy);
            return Mono.fromFuture(legacyEndpoint).then().doOnCancel(this::cancel);
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                send(json.valueToTree(message));
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then().doOnCancel(this::cancel);
    }

    private void send(JsonNode message) throws IOException {
        String address = legacy ? legacyEndpoint.join() : endpoint;
        JsonNode id = message.get("id");
        if (message.path("method").asText().equals("initialize")) {
            protocolVersion = message.path("params").path("protocolVersion").asText();
        }
        Runnable beforeSend = message.path("method").asText().equals("tools/call") ? beforeToolSend : () -> {
        };
        try (var exchange = network.execute("POST", address, json.writeValueAsBytes(message), headers(null),
            beforeSend)) {
            Response response = exchange.response();
            successful(response);
            if (legacy) {
                return;
            }
            captureSession(response);
            if (id == null || message.has("result") || message.has("error")) {
                if (response.code() != 202 && response.body().contentLength() > 0) {
                    throw unavailable();
                }
                return;
            }
            String type = response.header("Content-Type", "").split(";", 2)[0].trim();
            if (type.equals("application/json")) {
                JsonNode value = incoming.readTree(responseBudget.wrap(response.body().byteStream()));
                if (!receive(value, id)) {
                    throw unavailable();
                }
                return;
            }
            if (!type.equals("text/event-stream")) {
                throw unavailable();
            }
            var cursor = new Cursor();
            try {
                if (readEvents(response, id, cursor)) {
                    return;
                }
            } catch (IOException interrupted) {
                if (cursor.id == null || cursor.id.isBlank()) {
                    throw interrupted;
                }
            }
            resume(id, cursor);
        }
    }

    private void resume(JsonNode requestId, Cursor cursor) throws IOException {
        // 事件流断开后只读取既有请求的后续事件，绝不重发原 POST。
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cursor.id == null || cursor.id.isBlank()) {
                throw unavailable();
            }
            try {
                Thread.sleep(Math.min(cursor.retryMillis, network.remainingMillis()));
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw unavailable(cancelled);
            }
            try (var exchange = network.execute("GET", endpoint, null, headers(cursor.id))) {
                Response response = exchange.response();
                successful(response);
                if (!response.header("Content-Type", "").startsWith("text/event-stream")) {
                    throw unavailable();
                }
                if (readEvents(response, requestId, cursor)) {
                    return;
                }
            }
        }
        throw unavailable();
    }

    private boolean readEvents(Response response, JsonNode requestId, Cursor cursor) throws IOException {
        AtomicBoolean completed = new AtomicBoolean();
        McpEventReader.read(response.body().byteStream(), responseBudget, event -> {
            cursor.id = event.id();
            cursor.retryMillis = event.retryMillis();
            if (event.data().isBlank() || !event.type().equals("message")) {
                return true;
            }
            try {
                if (receive(incoming.readTree(event.data()), requestId)) {
                    completed.set(true);
                }
                return !completed.get();
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        });
        return completed.get();
    }

    private void readLegacy() {
        try (var exchange = network.execute("GET", endpoint, null, headers(null))) {
            Response response = exchange.response();
            successful(response);
            if (!response.header("Content-Type", "").startsWith("text/event-stream")) {
                throw unavailable();
            }
            McpEventReader.read(response.body().byteStream(), responseBudget, event -> {
                if (event.type().equals("endpoint")) {
                    HttpUrl source = HttpUrl.get(endpoint), destination = source.resolve(event.data());
                    if (destination == null || !OutboundAddressPolicy.sameOrigin(source, destination)
                        || !legacyEndpoint.complete(destination.toString())) {
                        throw unavailable();
                    }
                } else if (event.type().equals("message") && !event.data().isBlank()) {
                    try {
                        receive(incoming.readTree(event.data()), null);
                    } catch (IOException failure) {
                        throw unavailable(failure);
                    }
                }
                return !closed.get();
            });
            if (!closed.get()) {
                throw unavailable();
            }
        } catch (Exception failure) {
            legacyEndpoint.completeExceptionally(unavailable(failure));
            if (!closed.get()) {
                failureHandler.accept(failure instanceof ApiException ? failure : unavailable(failure));
            }
        }
    }

    private boolean receive(JsonNode value, JsonNode expectedId) throws IOException {
        if (value == null || !value.isObject() || !value.path("jsonrpc").asText().equals("2.0")) {
            throw unavailable();
        }
        if (value.has("method") && value.has("id")) {
            // 本项目没有服务端采样、提问或其他主动请求能力，明确返回方法不可用。
            var rejected = json.createObjectNode().put("jsonrpc", "2.0");
            rejected.set("id", value.get("id"));
            rejected.putObject("error").put("code", -32601).put("message", "此客户端不支持该请求");
            send(rejected);
            return false;
        }
        if (value.has("method")) {
            return false;
        }
        if (!value.has("id") || (!value.has("result") && !value.has("error"))) {
            throw unavailable();
        }
        if (value.path("result").has("protocolVersion")) {
            protocolVersion = value.path("result").path("protocolVersion").asText();
        }
        var parsed = McpSchema.deserializeJsonRpcMessage(protocolJson, json.writeValueAsString(value));
        handler.apply(Mono.just(parsed)).block(Duration.ofMillis(network.remainingMillis()));
        return expectedId != null && expectedId.equals(value.get("id"));
    }

    private Map<String, String> headers(String lastId) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", protocolVersion);
        if (sessionId != null) {
            headers.put("MCP-Session-Id", sessionId);
        }
        if (lastId != null) {
            headers.put("Last-Event-ID", lastId);
        }
        return headers;
    }

    private void captureSession(Response response) {
        String assigned = response.header("MCP-Session-Id");
        if (assigned == null) {
            return;
        }
        if (assigned.isEmpty() || assigned.length() > 1024 || !assigned.chars().allMatch(c -> c >= 33 && c <= 126)
            || (sessionId != null && !sessionId.equals(assigned))) {
            throw unavailable();
        }
        sessionId = assigned;
    }

    private void successful(Response response) {
        if (response.code() == 401 || response.code() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_AUTH_FAILED",
                "远程服务未接受当前凭据，请检查后重试。");
        }
        if (!response.isSuccessful()) {
            throw unavailable();
        }
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return protocolJson.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::cancel);
    }

    public void cancel() {
        if (closed.compareAndSet(false, true)) {
            network.close();
            legacyEndpoint.completeExceptionally(unavailable());
        }
    }

    private static ApiException unavailable() {
        return unavailable(null);
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED",
            "无法完成远程工具连接，请检查服务配置或稍后重试。", cause);
    }

    private static final class Cursor {
        private String id;
        private long retryMillis = 1000;
    }
}
