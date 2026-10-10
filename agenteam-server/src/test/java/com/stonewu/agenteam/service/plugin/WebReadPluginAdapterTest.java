package com.stonewu.agenteam.service.plugin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebReadPluginAdapterTest {
    @Test
    void rejectedWebsiteRecordsStatusAndTraceWithoutQueryOrResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "password=hidden-response-secret 私人正文".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Request-Id", "trace-123");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var mapper = new ObjectMapper();
        var json = new ResourceJson(mapper);
        var adapter = new WebReadPluginAdapter(new RestrictedHttpClient(new OutboundAddressPolicy(origin)), json,
            new ToolSchemaValidation());
        var logger = (Logger) LoggerFactory.getLogger(WebReadPluginAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var control = new ToolCallControl(() -> {
        })) {
            var failure = assertThrows(ApiException.class, () -> adapter.call("read_url", "operation-123",
                json.tree(Map.of("url", origin + "/page?q=private-query")), Duration.ofSeconds(5), control));
            assertEquals("REMOTE_REQUEST_REJECTED", failure.code());
            String log = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertTrue(log.contains("429"));
            assertTrue(log.contains("trace-123"));
            assertTrue(log.contains("operation-123"));
            assertTrue(log.contains("GET"));
            assertTrue(log.contains("/page"));
            assertFalse(log.contains("private-query"));
            assertFalse(log.contains("hidden-response-secret"));
            assertFalse(log.contains("私人正文"));
            assertNotNull(appender.list.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            server.stop(0);
        }
    }
}
