package com.stonewu.agenteam.service.data.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.data.HttpDataQueryMapper;
import com.stonewu.agenteam.mapper.data.HttpDataResponseMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.model.security.entity.HttpConnectionCredentials;
import com.stonewu.agenteam.service.data.DataValueCodec;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.security.HttpCredentialService;
import com.stonewu.agenteam.support.HttpsDataTestServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 HTTPS、证书验证、精确字段值和请求边界，不以模拟客户端代替实际网络。
 */
class HttpDataQueryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsOneFixedEndpointWithOnlyAllowedParametersAndExactMappedValues() throws Exception {
        try (var server = new HttpsDataTestServer()) {
            var requests = new AtomicInteger();
            var query = new AtomicReference<String>();
            server.handle("/records", exchange -> {
                requests.incrementAndGet();
                query.set(exchange.getRequestURI().getRawQuery());
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("Bearer isolated-http-secret", exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, "{\"items\":[{\"title\":\"中文 & next=5\",\"id\":18446744073709551615,\"amount\":0.100000000000000000000000000001,\"person\":{\"name\":\"张三\"}}],\"next\":\"/never-fetch\"}");
            });
            server.handle("/never-fetch", exchange -> {
                requests.incrementAndGet();
                respond(exchange, "[]");
            });
            var title = field("title", "string", 0);
            var id = field("id", "integer", 1);
            var amount = field("amount", "decimal", 2);
            var person = field("/person/name", "string", 3);
            var plan = new DataQueryPlan(collection("/items"), 1, List.of(id, amount, person),
                List.of(new DataQueryPlan.Condition(title, "eq", json.valueToTree("中文 & next=5"))), List.of(), 50, 0);
            var reader = reader(server.client());
            var mapper = new HttpDataQueryMapper(reader, new HttpDataResponseMapper(json, new DataValueCodec()), json);
            var result = mapper.query("enterprise", config(server.origin() + "/records?fixed=1"), plan, deadline(), 10000);
            assertEquals(1, requests.get());
            assertEquals("fixed=1&title=%E4%B8%AD%E6%96%87%20%26%20next%3D5", query.get());
            assertEquals("18446744073709551615", result.rows().getFirst().path("id").asText());
            assertEquals("0.100000000000000000000000000001", result.rows().getFirst().path("amount").asText());
            assertEquals("张三", result.rows().getFirst().path("/person/name").asText());
            assertFalse(result.hasMore());
        }
    }

    @Test
    void rejectsInvalidStructuresOversizedResponsesAndUntrustedCertificates() throws Exception {
        try (var server = new HttpsDataTestServer()) {
            server.handle("/large", exchange -> respond(exchange, "[\"" + "a".repeat(1024 * 1024) + "\"]"));
            server.handle("/duplicate", exchange -> respond(exchange, "{\"items\":[],\"items\":[]}"));
            server.handle("/valid", exchange -> respond(exchange, "[]"));
            var reader = reader(server.client());
            assertEquals("DATA_HTTP_RESPONSE_TOO_LARGE", assertThrows(ApiException.class, () -> reader.read("enterprise", config(server.origin() + "/large"), List.of(), deadline())).code());
            assertEquals("DATA_HTTP_READ_FAILED", assertThrows(ApiException.class, () -> reader.read("enterprise", config(server.origin() + "/duplicate"), List.of(), deadline())).code());
            var mapping = new HttpDataResponseMapper(json, new DataValueCodec());
            assertThrows(ApiException.class, () -> mapping.rows(json.readTree("{\"items\":[{\"other\":5}]}"), "/items", List.of(field("id", "integer", 0)), deadline()));
            assertThrows(ApiException.class, () -> mapping.rows(json.readTree("{\"items\":{}}"), "/items", List.of(), deadline()));
            String local = server.origin().replace("source.example.test", "127.0.0.1");
            var untrusted = reader(new RestrictedHttpClient(new OutboundAddressPolicy(local)));
            assertThrows(ApiException.class, () -> untrusted.read("enterprise", config(local + "/valid"), List.of(), deadline()));
        }
    }

    @Test
    void appliesFilteringOrderingPagingAndStopsAStalledResponseOnInterruption() throws Exception {
        try (var server = new HttpsDataTestServer()) {
            server.handle("/rows", exchange -> respond(exchange, "[{\"id\":null},{\"id\":3},{\"id\":2},{\"id\":1}]"));
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            server.handle("/slow", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            var id = field("id", "integer", 0);
            var reader = reader(server.client());
            var mapper = new HttpDataQueryMapper(reader, new HttpDataResponseMapper(json, new DataValueCodec()), json);
            var plan = new DataQueryPlan(collection("$"), 1, List.of(id), List.of(new DataQueryPlan.Condition(id, "gte", json.valueToTree("1"))), List.of(new DataQueryPlan.Order(id, "desc")), 1, 1);
            var result = mapper.query("enterprise", config(server.origin() + "/rows"), plan, deadline(), 1000);
            assertEquals("2", result.rows().getFirst().path("id").asText());
            assertTrue(result.hasMore());
            var failure = new AtomicReference<Throwable>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    reader.read("enterprise", config(server.origin() + "/slow"), List.of(), deadline());
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            });
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                long start = System.nanoTime();
                worker.interrupt();
                worker.join(3000);
                assertFalse(worker.isAlive());
                assertTrue(failure.get() instanceof ApiException);
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            } finally {
                release.countDown();
                worker.interrupt();
                worker.join(1000);
            }
        }
    }

    private HttpDataSourceReader reader(RestrictedHttpClient http) {
        var credentials = mock(HttpCredentialService.class);
        when(credentials.resolve("enterprise", "credential")).thenReturn(new HttpConnectionCredentials(Map.of("Authorization", "Bearer isolated-http-secret"), List.of("isolated-http-secret")));
        return new HttpDataSourceReader(http, credentials);
    }

    private ObjectNode config(String endpoint) {
        return json.valueToTree(Map.of("sourceType", "http", "credentialId", "credential", "connection", Map.of("endpoint", endpoint, "queryParameters", List.of("title")), "timeoutSeconds", 10));
    }

    private DataField field(String name, String type, int ordinal) {
        return new DataField(name, name, type, true, true, true, false, true, ordinal);
    }

    private DataCollectionRecord collection(String path) {
        return new DataCollectionRecord("collection", "enterprise", "resource", "数据", path, 1, null, null, "active", 1, Instant.now(), Instant.now());
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        try {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
