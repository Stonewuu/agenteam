package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 测试专用平台网络端点；响应取自有官方文档依据的协议样本，完整收集实际请求。
 */
public final class MockChannelServer implements AutoCloseable {
    public record Received(String method, URI uri, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst().map(entry -> entry.getValue().getFirst()).orElse(null);
        }

        public Map<String, String> query() {
            return parameters(uri.getRawQuery());
        }

        public Map<String, String> form() {
            return parameters(body);
        }

        private static Map<String, String> parameters(String text) {
            var result = new LinkedHashMap<String, String>();
            if (text != null && !text.isEmpty()) {
                for (String pair : text.split("&")) {
                    String[] parts = pair.split("=", 2);
                    result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
                }
            }
            return result;
        }
    }

    private record Reply(int status, String body, Map<String, String> headers, boolean disconnect) {
    }

    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;
    private final JsonNode fixtures;

    public MockChannelServer() throws IOException {
        try (var stream = new ClassPathResource("contracts/integration/platform-responses.json").getInputStream()) {
            fixtures = new ObjectMapper().readTree(stream);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI(),
                    Map.copyOf(exchange.getRequestHeaders()), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Reply reply = replies.poll();
                if (reply == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                if (reply.disconnect()) {
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void reply(String fixture) {
        reply(200, fixture, Map.of());
    }

    public void reply(int status, String fixture, Map<String, String> headers) {
        if (!fixtures.has(fixture)) {
            throw new IllegalArgumentException("未知平台协议样本：" + fixture);
        }
        raw(status, fixtures.get(fixture).toString(), headers);
    }

    public void raw(int status, String body, Map<String, String> headers) {
        replies.add(new Reply(status, body, headers, false));
    }

    public void disconnect() {
        replies.add(new Reply(200, "", Map.of(), true));
    }

    public void reset() {
        replies.clear();
        received.clear();
    }

    public Received take() throws InterruptedException {
        var value = received.poll(3, TimeUnit.SECONDS);
        if (value == null) {
            throw new AssertionError("没有收到预期的平台请求");
        }
        return value;
    }

    public int remainingRequests() {
        return received.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
