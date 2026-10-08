package com.stonewu.agenteam.service.network;

import com.stonewu.agenteam.service.http.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接验证实际套接字、解析次数、跨域凭据和取消，不只检查地址字符串。
 */
class RestrictedHttpClientTest {
    @Test
    void deniesPrivateAddressesAndMixedDnsAnswersByDefault() throws Exception {
        var policy = new OutboundAddressPolicy("");
        for (String address : List.of("127.0.0.1", "127.1", "2130706433", "10.1.2.3", "172.16.1.1", "192.168.1.1",
            "169.254.169.254", "100.100.100.200", "[::1]", "[::ffff:127.0.0.1]", "[fc00::1]", "[fe80::1]")) {
            assertThrows(ApiException.class, () -> policy.resolve(policy.validateUrl("https://" + address)), address);
        }
        assertThrows(ApiException.class, () -> policy.validateUrl("https://user:password@example.com"));
        assertThrows(ApiException.class, () -> policy.validateUrl("https://example.com?api_key=hidden"));
        assertThrows(ApiException.class, () -> policy.validateUrl("http://example.com"));
        var mixed = new OutboundAddressPolicy("", ignored -> List.of(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")));
        assertThrows(ApiException.class, () -> mixed.resolve(mixed.validateUrl("https://example.com")));
        var metadata = new OutboundAddressPolicy("http://169.254.169.254");
        assertThrows(ApiException.class, () -> metadata.resolve(metadata.validateUrl("http://169.254.169.254")));
    }

    @Test
    void connectsOnlyToCheckedDnsAddressAndDoesNotResolveAgain() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "已经连接到验证过的地址".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String origin = "http://pinned.example:" + server.getAddress().getPort();
        var lookups = new AtomicInteger();
        var policy = new OutboundAddressPolicy(origin, ignored -> {
            assertEquals(1, lookups.incrementAndGet(), "实际连接不能再次解析并采用另一个地址");
            return List.of(InetAddress.getByName("127.0.0.1"));
        });
        try (var scope = new RestrictedHttpClient(policy).open(origin, Map.of(), Duration.ofSeconds(3));
             var result = scope.execute("GET", origin, null, Map.of())) {
            assertEquals("已经连接到验证过的地址", result.response().body().string());
            assertEquals(1, lookups.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossOriginRedirectDropsCredentialsAndSessionButPreservesPostOnce() throws Exception {
        var first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + first.getAddress().getPort();
        String destination = "http://127.0.0.1:" + second.getAddress().getPort();
        var count = new AtomicInteger();
        var received = new AtomicReference<Map<String, List<String>>>();
        var body = new AtomicReference<String>();
        first.createContext("/", exchange -> {
            assertEquals("Bearer test-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", destination);
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        second.createContext("/", exchange -> {
            count.incrementAndGet();
            received.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        first.start();
        second.start();
        try (var scope = new RestrictedHttpClient(new OutboundAddressPolicy(origin + "," + destination))
            .open(origin, Map.of("Authorization", "Bearer test-secret", "X-API-Key", "test-key"), Duration.ofSeconds(3));
             var result = scope.execute("POST", origin, "{\"text\":\"确认的正文\"}".getBytes(StandardCharsets.UTF_8), Map.of("MCP-Session-Id", "private-session"))) {
            assertEquals(200, result.response().code());
            assertEquals(1, count.get());
            assertEquals("{\"text\":\"确认的正文\"}", body.get());
            assertNull(received.get().get("Authorization"));
            assertNull(received.get().get("X-api-key"));
            assertNull(received.get().get("Mcp-session-id"));
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    @Test
    void redirectCannotReachUnlistedLoopbackPort() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        try (var scope = new RestrictedHttpClient(new OutboundAddressPolicy(origin)).open(origin, Map.of(), Duration.ofSeconds(3))) {
            assertEquals("NETWORK_ADDRESS_DENIED", assertThrows(ApiException.class, () -> scope.execute("GET", origin, null, Map.of())).code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledDnsLookupIsBoundedByTheOperationTimeout() throws Exception {
        var policy = new OutboundAddressPolicy("", ignored -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getByName("93.184.216.34"));
        });
        long began = System.nanoTime();
        try (var scope = new RestrictedHttpClient(policy).open("https://example.com", Map.of(), Duration.ofMillis(150))) {
            assertEquals("REMOTE_TIMEOUT", assertThrows(ApiException.class, () -> scope.execute("GET", "https://example.com", null, Map.of())).code());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(2)) < 0);
        }
    }

    @Test
    void cancellationInterruptsWaitingResponsePromptly() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                reached.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            try (var scope = new RestrictedHttpClient(new OutboundAddressPolicy(origin)).open(origin, Map.of(), Duration.ofSeconds(10))) {
                var pending = CompletableFuture.runAsync(() -> {
                    assertThrows(Exception.class, () -> {
                        try (var ignored = scope.execute("POST", origin, new byte[0], Map.of())) {
                        }
                    });
                }, executor);
                assertTrue(reached.await(2, TimeUnit.SECONDS));
                scope.close();
                pending.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }
}
