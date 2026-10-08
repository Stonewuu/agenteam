package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.controller.sandbox.SandboxContainerCleanupController;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.SandboxContainerCleanup;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSandboxContainerCleanupTest {
    private static final String TOKEN = "cleanup-test-token-at-least-thirty-two-characters";
    private static final String SCOPE = "a".repeat(64);
    private final SandboxContainerCleanup containers = mock(SandboxContainerCleanup.class);
    private HttpServer server;
    private HttpClient http;
    private String address;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/container-cleanup/", new SandboxContainerCleanupController(containers, new ObjectMapper(), TOKEN));
        server.start();
        http = HttpClient.newHttpClient();
        address = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
        http.close();
    }

    @Test
    void cleansBothKindsOnExecutionNodeAndAllowsRepeatedRequests() {
        var remote = new RemoteSandboxContainerCleanup(address, TOKEN);
        remote.removeUserContainer(SCOPE, Duration.ofSeconds(5));
        remote.removeUserContainer(SCOPE, Duration.ofSeconds(5));
        try (var control = new ToolCallControl(() -> {
        })) {
            remote.removeForWorkspace(SCOPE, Duration.ofSeconds(5), control);
        }
        verify(containers, times(2)).removeUserContainer(SCOPE, Duration.ofSeconds(30));
        verify(containers).removeForWorkspace(SCOPE, Duration.ofSeconds(30), null);
    }

    @Test
    void rejectsMissingOrWrongCredentialsBeforeCleaning() throws Exception {
        String path = "container-cleanup/user/" + SCOPE;
        assertEquals(401, request(path, "DELETE", null).statusCode());
        assertEquals(401, request(path, "DELETE", "wrong-token").statusCode());
        verifyNoInteractions(containers);
    }

    @Test
    void rejectsArbitraryContainerNamesPathsQueriesAndMethods() throws Exception {
        for (String path : List.of("container-cleanup/user/mysql", "container-cleanup/user/../" + SCOPE,
            "container-cleanup/other/" + SCOPE, "container-cleanup/user/" + SCOPE + "?force=true")) {
            assertEquals(404, request(path, "DELETE", TOKEN).statusCode());
        }
        assertEquals(405, request("container-cleanup/user/" + SCOPE, "POST", TOKEN).statusCode());
        var remote = new RemoteSandboxContainerCleanup(address, TOKEN);
        assertThrows(ApiException.class, () -> remote.removeUserContainer("mysql", Duration.ofSeconds(5)));
        verifyNoInteractions(containers);
    }

    @Test
    void reportsCleanupFailureAndCanRetryWithoutDiscardingIt() {
        doThrow(new IllegalStateException("测试节点暂时无法清理容器")).doNothing()
            .when(containers).removeUserContainer(SCOPE, Duration.ofSeconds(30));
        var remote = new RemoteSandboxContainerCleanup(address, TOKEN);
        var failure = assertThrows(ApiException.class, () -> remote.removeUserContainer(SCOPE, Duration.ofSeconds(5)));
        assertEquals(503, failure.getStatusCode().value());
        assertNotNull(failure.getCause());
        remote.removeUserContainer(SCOPE, Duration.ofSeconds(5));
        verify(containers, times(2)).removeUserContainer(SCOPE, Duration.ofSeconds(30));
    }

    @Test
    void refusesUnencryptedRemoteAddressAndDoesNotFollowRedirects() {
        assertThrows(IllegalArgumentException.class,
            () -> new RemoteSandboxContainerCleanup("http://execution.example.test", TOKEN));
        var redirected = new AtomicInteger();
        server.createContext("/container-cleanup/user/" + SCOPE, exchange -> {
            exchange.getResponseHeaders().set("Location", address + "unexpected");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        var remote = new RemoteSandboxContainerCleanup(address, TOKEN);
        assertThrows(ApiException.class, () -> remote.removeUserContainer(SCOPE, Duration.ofSeconds(5)));
        assertEquals(0, redirected.get());
        verifyNoInteractions(containers);
    }

    private HttpResponse<String> request(String path, String method, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(address + path)).timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
