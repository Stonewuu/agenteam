package com.stonewu.agenteam.service.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.modelprofile.RemoteModelMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteModelClientTest {
    private HttpServer server;
    private final RemoteModelMapper mapper = new RemoteModelMapper(new ObjectMapper());
    private final OutboundAddressPolicy policy = new OutboundAddressPolicy("");
    private final RemoteModelClient client = new RemoteModelClient(policy, mapper);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void appendsModelsToTheSavedBasePathAndReadsCurrentCredentialsEveryTime() {
        var header = new AtomicReference<String>();
        server.createContext("/custom/v1/models", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"chat-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        assertEquals("chat-model", client.list(provider("first-key")).getFirst().id());
        assertEquals("Bearer first-key", header.get());
        client.list(provider("rotated-key"));
        assertEquals("Bearer rotated-key", header.get());
        client.list(provider(""));
        assertNull(header.get());
    }

    @Test
    void doesNotFollowRedirectsOrExposeRemoteErrorBodies() {
        var followed = new AtomicInteger();
        var status = new AtomicInteger(302);
        server.createContext("/custom/v1/models", exchange -> {
            exchange.getResponseHeaders().add("Location", "/unexpected");
            byte[] body = "secret-from-provider".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/unexpected", exchange -> {
            followed.incrementAndGet();
            exchange.close();
        });
        for (int code : new int[]{302, 401, 403, 404, 429, 500}) {
            status.set(code);
            var failure = assertThrows(ApiException.class, () -> client.list(provider("private-key")));
            assertEquals(502, failure.getStatusCode().value());
            assertFalse(failure.getMessage().contains("secret-from-provider"));
            assertFalse(failure.getMessage().contains("private-key"));
        }
        assertEquals(0, followed.get());
    }

    @Test
    void boundsResponseSizeAndWaitTime() {
        server.createContext("/custom/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, 5 * 1024 * 1024);
            exchange.close();
        });
        assertEquals("MODEL_LIST_TOO_LARGE", assertThrows(ApiException.class, () -> client.list(provider(""))).code());
        server.removeContext("/custom/v1/models");
        server.createContext("/custom/v1/models", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        var impatient = new RemoteModelClient(policy, mapper, new OkHttpClient.Builder()
            .readTimeout(Duration.ofMillis(100)).callTimeout(Duration.ofMillis(200)).build());
        var timeout = assertThrows(ApiException.class, () -> impatient.list(provider("")));
        assertEquals("MODEL_LIST_TIMEOUT", timeout.code());
        assertNotNull(timeout.getCause());
    }

    private ModelProviderRecord provider(String key) {
        return new ModelProviderRecord("provider", "enterprise", "本机模型", "openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1/", key, true, 1, Instant.now(), Instant.now());
    }
}
