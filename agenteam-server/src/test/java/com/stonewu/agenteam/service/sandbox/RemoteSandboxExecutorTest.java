package com.stonewu.agenteam.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.controller.sandbox.SandboxServerController;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceCommands;
import com.stonewu.agenteam.service.workspace.DockerWorkspaceRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("external-process")
class RemoteSandboxExecutorTest {
    @TempDir
    Path directory;

    @Test
    void transfersFilesBetweenDifferentRootsAndAuthenticatesEveryRequest() throws Exception {
        String token = "test-sandbox-access-token-with-32-characters";
        var settings = new WorkspaceSettings(directory.toString(), "python:3.13-slim", true, 32, 2, 100, 256, 1, 32, 30, "none");
        var docker = new DockerWorkspaceRunner(settings, new DockerWorkspaceCommands(), new SandboxRuntimeSettings(64, 1, "", ""));
        try (var service = new SandboxExecutionService(docker, settings, new ObjectMapper(), directory.resolve("server"), 2);
             var threads = Executors.newVirtualThreadPerTaskExecutor();
             var client = HttpClient.newHttpClient()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(threads);
            server.createContext("/executions/", new SandboxServerController(service, token));
            server.start();
            try {
                String origin = "http://127.0.0.1:" + server.getAddress().getPort();
                var remote = new RemoteSandboxExecutor(settings, new ObjectMapper(), origin, token);
                Path clientRoot = Files.createDirectory(directory.resolve("client"));
                Path source = clientRoot.resolve("source");
                for (String area : List.of("inputs", "tool-results", "work", "outputs")) {
                    Files.createDirectories(source.resolve(area));
                }
                Files.writeString(source.resolve("inputs/原件.txt"), "不可修改的原件");
                try (var control = new ToolCallControl(() -> {
                })) {
                    var result = remote.execute(source, clientRoot.resolve("result"), clientRoot, "remote-test",
                        "cat /workspace/inputs/原件.txt > /workspace/outputs/结果.txt", "work", Duration.ofSeconds(20), control);
                    assertEquals(0, result.exitCode());
                    assertEquals("不可修改的原件", Files.readString(clientRoot.resolve("result/outputs/结果.txt")));
                    assertFalse(result.stdoutPath().contains(directory.toString()));
                }
                String cancelled = UUID.randomUUID().toString();
                var address = URI.create(origin + "/executions/" + cancelled);
                assertEquals(401, client.send(HttpRequest.newBuilder(address).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(204, client.send(HttpRequest.newBuilder(address).header("Authorization", "Bearer " + token).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals("SANDBOX_CANCELLED", assertThrows(ApiException.class, () -> service.execute(cancelled, InputStream.nullInputStream())).code());
                try (var control = new ToolCallControl(() -> {
                })) {
                    var task = threads.submit(() -> remote.execute(source, clientRoot.resolve("cancelled"), clientRoot, "remote-cancel",
                        "sleep 60", "work", Duration.ofSeconds(25), control));
                    awaitJobDirectories(true);
                    control.close();
                    assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS));
                    assertFalse(Files.exists(clientRoot.resolve("cancelled/outputs")));
                }
                awaitJobDirectories(false);
                try (var control = new ToolCallControl(() -> {
                })) {
                    var task = threads.submit(() -> remote.execute(source, clientRoot.resolve("interrupted"), clientRoot, "remote-interrupt",
                        "sleep 60", "work", Duration.ofSeconds(25), control));
                    awaitJobDirectories(true);
                    task.cancel(true);
                    awaitJobDirectories(false);
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private void awaitJobDirectories(boolean expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            try (var entries = Files.list(directory.resolve("server"))) {
                if (entries.anyMatch(Files::isDirectory) == expected) {
                    return;
                }
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail(expected ? "独立节点未接收到任务" : "任务结束后独立节点没有清理临时目录");
    }

    @AfterEach
    void cleanIncompleteJobs() throws Exception {
        Path root = directory.resolve("server");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var jobs = Files.list(root)) {
            for (var job : jobs.filter(Files::isDirectory).toList()) {
                if (Files.isRegularFile(job.resolve("sandbox.pending"))) {
                    new DockerWorkspaceCommands().removeForWorkspace(Utf8Text.revision("workspace", job.toAbsolutePath().normalize().toString()));
                }
            }
        }
    }
}
