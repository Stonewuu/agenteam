package com.stonewu.agenteam.service.environment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.http.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteUserEnvironmentRuntimeTest {
    @Test
    void reportsStatusAndCodeButNeverIncludesMalformedResponseContent() throws Exception {
        String secret = "sensitive-response-marker";
        var counter = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/environments", exchange -> {
            try {
                boolean malformed = counter.getAndIncrement() == 0;
                byte[] bytes = (malformed ? secret : "{\"code\":\"ENVIRONMENT_BUSY\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(malformed ? 200 : 409, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        var logger = (Logger) LoggerFactory.getLogger(RemoteUserEnvironmentRuntime.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        server.start();
        try {
            var remote = new RemoteUserEnvironmentRuntime(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "environment-test-token-with-at-least-32-characters");
            var project = new ProjectLocation("workspace", "user-environment", "enterprise", "alice", "environment");
            var malformed = assertThrows(ApiException.class, () -> remote.manage("status", project));
            var trace = new StringWriter();
            malformed.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(secret));
            assertTrue(trace.toString().contains("JsonParseException"));
            assertTrue(trace.toString().contains("RemoteUserEnvironmentRuntime.manage"));
            assertEquals("ENVIRONMENT_BUSY", assertThrows(ApiException.class, () -> remote.manage("stop", project)).code());
            String messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + right);
            assertTrue(messages.contains("409"));
            assertTrue(messages.contains("ENVIRONMENT_BUSY"));
            assertTrue(messages.contains("/environments"));
            assertFalse(messages.contains(secret));
        } finally {
            server.stop(0);
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
